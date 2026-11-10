package org.spring_web.spring_boot_service.security;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Redis 中的令牌状态：刷新令牌一次性轮换、访问令牌黑名单、用户令牌版本。
 *
 * <p>轮换必须用 Lua 保证「读 + 删」原子：否则两个并发刷新请求都能读到同一个
 * refreshToken，都拿到新令牌，就破坏了一次性语义。这是本项目最容易被忽略的并发点。</p>
 */
@Component
@RequiredArgsConstructor
public class RefreshTokenStore {

    private static final DefaultRedisScript<String> CONSUME_SCRIPT = new DefaultRedisScript<>(
            "local v = redis.call('GET', KEYS[1]); "
                    + "if v then redis.call('DEL', KEYS[1]); end; "
                    + "return v",
            String.class);

    private final StringRedisTemplate redis;

    private String versionKey(long uid) {
        return "sss:token:version:" + uid;
    }

    private String refreshKey(String jti) {
        return "sss:token:refresh:" + jti;
    }

    private String blacklistKey(String jti) {
        return "sss:token:blacklist:" + jti;
    }

    public long tokenVersion(long uid) {
        String value = redis.opsForValue().get(versionKey(uid));
        return value == null ? 0L : Long.parseLong(value);
    }

    /** 改密码 / 强制下线：递增版本，令所有旧令牌失效。 */
    public long bumpTokenVersion(long uid) {
        Long value = redis.opsForValue().increment(versionKey(uid));
        return value == null ? 1L : value;
    }

    public void saveRefresh(String jti, long uid, Duration ttl) {
        redis.opsForValue().set(refreshKey(jti), String.valueOf(uid), ttl);
    }

    /** 原子消费，返回令牌所属用户；已被使用或过期返回 empty。 */
    public Optional<Long> consumeRefresh(String jti) {
        String value = redis.execute(CONSUME_SCRIPT, List.of(refreshKey(jti)));
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(value));
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }

    public void blacklist(String jti, Duration ttl) {
        if (jti != null) {
            redis.opsForValue().set(blacklistKey(jti), "1", ttl);
        }
    }

    public boolean isBlacklisted(String jti) {
        return jti != null && Boolean.TRUE.equals(redis.hasKey(blacklistKey(jti)));
    }
}
