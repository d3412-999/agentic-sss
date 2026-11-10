package org.spring_web.spring_boot_service.config;

import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.math.BigInteger;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 雪花 ID 是 19 位 Long，JS Number 只能安全表示 2^53-1。
 *
 * <p>API 文档明确要求 ID 序列化为字符串，这里做全局兜底；DTO 层仍显式使用 String，
 * 双保险避免前端精度丢失。注意只注册包装类型，primitive long（分页的 total 等）保持数字。</p>
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer idAsStringCustomizer() {
        return builder -> builder
                .serializerByType(Long.class, ToStringSerializer.instance)
                .serializerByType(BigInteger.class, ToStringSerializer.instance);
    }
}
