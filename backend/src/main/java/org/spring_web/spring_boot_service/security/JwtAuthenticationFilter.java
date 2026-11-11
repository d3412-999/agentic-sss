package org.spring_web.spring_boot_service.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.spring_web.spring_boot_service.entity.SysUser;
import org.spring_web.spring_boot_service.mapper.SysUserMapper;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 每个请求解析 Bearer Token，并回查用户状态。
 *
 * <p>刻意回查 DB：教师把学生账号设为 DISABLED 后，该学生手里尚未过期的 Token
 * 必须立刻失效，否则「禁用」形同虚设。代价是每请求一次主键查询，可后续加缓存。</p>
 *
 * <p>令牌版本 {@code ver} 与 {@code sys_user.token_version} 比较：版本号迁到 MySQL 后，
 * Redis 被 flush 也不会让已吊销的令牌复活。访问令牌黑名单仍在 Redis，但 Redis 故障时
 * 必须<b>失败关闭</b>（拒绝本次鉴权）——否则 Redis 抖动等于黑名单失效，已登出的令牌
 * 会被重新放行。</p>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final JwtService jwtService;
    private final RefreshTokenStore tokenStore;
    private final SysUserMapper sysUserMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (StringUtils.hasText(header) && header.startsWith(BEARER)) {
            authenticate(header.substring(BEARER.length()), request);
        }
        chain.doFilter(request, response);
    }

    private void authenticate(String token, HttpServletRequest request) {
        try {
            Claims claims = jwtService.parse(token);
            if (!jwtService.isAccess(claims)) {
                return;
            }
            // 黑名单先在 Redis 查：已登出的令牌没必要再查库；Redis 异常时 blacklisted() 直接拒绝
            if (blacklisted(claims.getId())) {
                return;
            }
            long uid = Long.parseLong(claims.get("uid", String.class));
            long version = Long.parseLong(claims.get("ver", String.class));

            SysUser user = sysUserMapper.selectById(uid);
            if (user == null || !"ACTIVE".equals(user.getStatus())) {
                return;
            }
            // 版本以 DB 为准；历史数据 / 未执行 V4 迁移时 null 视为 0
            long currentVersion = user.getTokenVersion() == null ? 0L : user.getTokenVersion();
            if (version != currentVersion) {
                return;
            }

            LoginUser principal = LoginUser.from(user);
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(principal, null, principal.authorities());
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (JwtException | IllegalArgumentException ex) {
            SecurityContextHolder.clearContext();
            log.debug("Rejected JWT: {}", ex.getMessage());
        }
    }

    /**
     * 黑名单查询，失败关闭：Redis 任何异常都按「已拉黑」处理并清空上下文。
     *
     * <p>捕获 {@link DataAccessException}（Spring Data Redis 的异常父类）之后再兜底
     * {@link RuntimeException}（Lettuce / 序列化等可能漏出的运行时异常），确保没有任何
     * 一条路径会在 Redis 不可用时放行请求。</p>
     */
    private boolean blacklisted(String jti) {
        try {
            return tokenStore.isBlacklisted(jti);
        } catch (DataAccessException ex) {
            SecurityContextHolder.clearContext();
            log.warn("Redis 不可用，无法校验访问令牌黑名单，拒绝本次鉴权: {}", ex.getMessage());
            return true;
        } catch (RuntimeException ex) {
            SecurityContextHolder.clearContext();
            log.warn("Redis 校验访问令牌黑名单异常，拒绝本次鉴权", ex);
            return true;
        }
    }
}
