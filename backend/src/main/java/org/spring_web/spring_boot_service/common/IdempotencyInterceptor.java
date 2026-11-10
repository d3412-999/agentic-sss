package org.spring_web.spring_boot_service.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.spring_web.spring_boot_service.config.SssProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 幂等拦截器：对标注 {@link Idempotent} 且带 {@code Idempotency-Key} 的写请求去重。
 *
 * <p>实现要点：</p>
 * <ol>
 *   <li>{@code SETNX} 是原子操作，多实例部署下也不会重复受理；</li>
 *   <li>key 绑定当前用户，防止不同用户互相撞 key；</li>
 *   <li>业务失败（4xx/5xx 或抛异常）时删除 key，允许客户端修正后重试；</li>
 *   <li>成功后保留 key 至 TTL（默认 24h），重复提交返回 40902。</li>
 * </ol>
 * <p>当前策略是「拒绝重复」。若需要「重放首次响应」，可在 afterCompletion 里
 * 配合 ContentCachingResponseWrapper 缓存响应体，这是后续的增强点。</p>
 */
@Component
@RequiredArgsConstructor
public class IdempotencyInterceptor implements HandlerInterceptor {

    private static final String ATTRIBUTE = "sss.idempotency.redisKey";
    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final StringRedisTemplate redis;
    private final SssProperties properties;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)
                || !method.hasMethodAnnotation(Idempotent.class)
                || !WRITE_METHODS.contains(request.getMethod())) {
            return true;
        }
        String idempotencyKey = request.getHeader("Idempotency-Key");
        if (!StringUtils.hasText(idempotencyKey)) {
            return true;
        }

        String redisKey = "sss:idem:" + owner() + ":" + idempotencyKey;
        Duration ttl = Duration.ofHours(properties.getLimits().getIdempotencyTtlHours());
        Boolean acquired = redis.opsForValue().setIfAbsent(redisKey, "PROCESSING", ttl);
        if (!Boolean.TRUE.equals(acquired)) {
            throw new BizException(ErrorCode.DUPLICATE_OPERATION, "相同请求已受理，请勿重复提交");
        }
        request.setAttribute(ATTRIBUTE, redisKey);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        String redisKey = (String) request.getAttribute(ATTRIBUTE);
        if (redisKey == null) {
            return;
        }
        if (ex != null || response.getStatus() >= 400) {
            redis.delete(redisKey);
        } else {
            redis.opsForValue().set(redisKey, "DONE",
                    Duration.ofHours(properties.getLimits().getIdempotencyTtlHours()));
        }
    }

    private String owner() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getName() != null) {
            return "u:" + authentication.getName();
        }
        return "anon";
    }
}
