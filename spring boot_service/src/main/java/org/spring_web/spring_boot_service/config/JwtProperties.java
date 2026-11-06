package org.spring_web.spring_boot_service.config;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * JWT 配置项，供后续登录 / 刷新令牌逻辑使用。
 */
@Data
@Component
@ConfigurationProperties(prefix = "sss.jwt")
public class JwtProperties {

    /** 签名密钥（HS256 至少 32 字节）。 */
    private String secret = "change-me-change-me-change-me-change-me";

    /** Access Token 有效期。 */
    private Duration accessTokenTtl = Duration.ofHours(2);

    /** Refresh Token 有效期。 */
    private Duration refreshTokenTtl = Duration.ofDays(7);
}
