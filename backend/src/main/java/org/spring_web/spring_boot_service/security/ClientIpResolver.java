package org.spring_web.spring_boot_service.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.spring_web.spring_boot_service.common.TrustedProxyIp;
import org.spring_web.spring_boot_service.config.SssProperties;
import org.springframework.stereotype.Component;

/**
 * 真实客户端 IP 解析入口：登录失败限流与审计日志都走这里，保证两处口径一致。
 *
 * <p>是否采信 {@code X-Forwarded-For} 由 {@code sss.security.trusted-proxies} 决定：
 * 默认空列表 = 永不信任，直连地址即客户端；只有部署在反向代理后面时才需要配置代理的
 * IP / CIDR。具体解析规则见 {@link TrustedProxyIp}。</p>
 */
@Component
@RequiredArgsConstructor
public class ClientIpResolver {

    private static final String FORWARDED_FOR_HEADER = "X-Forwarded-For";

    private final SssProperties properties;

    /** 解析请求的真实客户端 IP；无法确定时返回直连地址。 */
    public String resolve(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        return TrustedProxyIp.resolve(request.getRemoteAddr(),
                request.getHeader(FORWARDED_FOR_HEADER), trustedProxies());
    }

    /** 过滤掉配置里的空白项，避免 {@code TRUSTED_PROXIES=","} 这类写法产生空规则。 */
    private List<String> trustedProxies() {
        List<String> configured = properties.getSecurity().getTrustedProxies();
        if (configured == null || configured.isEmpty()) {
            return List.of();
        }
        return configured.stream()
                .filter(entry -> entry != null && !entry.isBlank())
                .map(String::trim)
                .toList();
    }
}
