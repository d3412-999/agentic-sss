package org.spring_web.spring_boot_service.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常映射的「不能是 500」守卫。
 *
 * <p>背景：批量添加学生点「下一步」报红字「服务端异常」——真实原因只是后端进程还是加接口之前的
 * 旧构建，请求打到了没注册的路径上，Spring 抛 {@code NoResourceFoundException}，
 * 被 {@code Exception} 兜底成 50000。这类「前端调错路径 / 版本不一致」必须表现为 4xx，
 * 否则排障的人会去业务代码里找一个不存在的 bug。</p>
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void noHandlerBecomes404WithPathAndHint() {
        NoResourceFoundException ex =
                new NoResourceFoundException(HttpMethod.POST, "api/v1/teacher/offerings/9201/students/import/parse-text");

        var response = handler.handleNoResourceFound(ex);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        Result<Object> body = response.getBody();
        assertNotNull(body);
        assertEquals(ErrorCode.NOT_FOUND.getCode(), body.getCode());
        assertTrue(body.getMessage().contains("POST"), body.getMessage());
        assertTrue(body.getMessage().contains("/api/v1/teacher/offerings/9201/students/import/parse-text"),
                body.getMessage());
        assertTrue(body.getData() instanceof Map<?, ?> map && map.containsKey("hint"),
                "提示里要说明「前后端版本可能不一致」，否则用户只会看到「接口不存在」");
    }

    @Test
    void wrongHttpMethodBecomes405NotServerError() {
        HttpRequestMethodNotSupportedException ex =
                new HttpRequestMethodNotSupportedException("GET", java.util.List.of("POST"));

        var response = handler.handleMethodNotSupported(ex);

        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, response.getStatusCode());
        Result<Object> body = response.getBody();
        assertNotNull(body);
        assertEquals(ErrorCode.METHOD_NOT_ALLOWED.getCode(), body.getCode());
        assertTrue(body.getMessage().contains("GET"), body.getMessage());
    }

    @Test
    void unexpectedStill500() {
        var response = handler.handleUnexpectedException(new IllegalStateException("boom"));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(ErrorCode.INTERNAL_ERROR.getCode(), response.getBody().getCode());
    }
}
