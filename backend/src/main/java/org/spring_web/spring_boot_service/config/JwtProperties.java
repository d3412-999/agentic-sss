package org.spring_web.spring_boot_service.config;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** JWT 配置项。上线前务必把 sss.jwt.secret 换成 ≥32 字节的随机值。 */
@Data
@Component
@ConfigurationProperties(prefix = "sss.jwt")
public class JwtProperties {

    private String secret = "change-me-change-me-change-me-change-me";
    private Duration accessTokenTtl = Duration.ofHours(2);
    private Duration refreshTokenTtl = Duration.ofDays(7);
}
