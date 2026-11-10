package org.spring_web.spring_boot_service.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import lombok.RequiredArgsConstructor;
import org.spring_web.spring_boot_service.config.JwtProperties;
import org.springframework.stereotype.Component;

/**
 * JWT 签发与解析，使用 jjwt 0.12 的类型安全 API（不再用已废弃的 signWith(SignatureAlgorithm, String)）。
 *
 * <p>两类令牌都带 {@code ver}（用户令牌版本号）：改密码或管理员强制下线时
 * 只需在 Redis 里递增版本号，所有已签发令牌立即失效，无需维护黑名单列表。</p>
 */
@Component
@RequiredArgsConstructor
public class JwtService {

    private static final String TYP_ACCESS = "access";
    private static final String TYP_REFRESH = "refresh";

    private final JwtProperties properties;

    private SecretKey key() {
        byte[] secret = properties.getSecret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            throw new IllegalStateException("sss.jwt.secret 至少需要 32 字节");
        }
        return Keys.hmacShaKeyFor(secret);
    }

    public String issueAccess(LoginUser user, long tokenVersion) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.username())
                .id(UUID.randomUUID().toString())
                .claim("uid", String.valueOf(user.id()))
                .claim("role", user.role())
                .claim("name", user.name())
                .claim("ver", String.valueOf(tokenVersion))
                .claim("typ", TYP_ACCESS)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(properties.getAccessTokenTtl())))
                .signWith(key())
                .compact();
    }

    public String issueRefresh(LoginUser user, long tokenVersion, String jti) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.username())
                .id(jti)
                .claim("uid", String.valueOf(user.id()))
                .claim("ver", String.valueOf(tokenVersion))
                .claim("typ", TYP_REFRESH)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(properties.getRefreshTokenTtl())))
                .signWith(key())
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean isRefresh(Claims claims) {
        return TYP_REFRESH.equals(claims.get("typ", String.class));
    }

    public boolean isAccess(Claims claims) {
        return TYP_ACCESS.equals(claims.get("typ", String.class));
    }

    public long accessExpiresInSeconds() {
        return properties.getAccessTokenTtl().toSeconds();
    }

    public long refreshTtlSeconds() {
        return properties.getRefreshTokenTtl().toSeconds();
    }
}
