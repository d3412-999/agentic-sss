package org.spring_web.spring_boot_service.common;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记支持 {@code Idempotency-Key} 的写接口（创建开课、选题、发布成绩等）。
 *
 * <p>只标注文档声明「支持幂等」的接口，避免全局拦截导致正常重试被误判。</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {
}
