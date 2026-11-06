package org.spring_web.spring_boot_service.common;

import lombok.Data;
import org.slf4j.MDC;

/**
 * 统一响应包：{@code {code, message, data, traceId}}。
 */
@Data
public class Result<T> {

    private int code;
    private String message;
    private T data;
    private String traceId;

    public static <T> Result<T> ok() {
        return ok(null);
    }

    public static <T> Result<T> ok(T data) {
        Result<T> result = new Result<>();
        result.setCode(ErrorCode.SUCCESS.getCode());
        result.setMessage(ErrorCode.SUCCESS.getMessage());
        result.setData(data);
        result.setTraceId(currentTraceId());
        return result;
    }

    public static <T> Result<T> fail(ErrorCode errorCode) {
        return fail(errorCode, errorCode.getMessage(), null);
    }

    public static <T> Result<T> fail(ErrorCode errorCode, String message) {
        return fail(errorCode, message, null);
    }

    public static <T> Result<T> fail(ErrorCode errorCode, String message, T data) {
        Result<T> result = new Result<>();
        result.setCode(errorCode.getCode());
        result.setMessage(message);
        result.setData(data);
        result.setTraceId(currentTraceId());
        return result;
    }

    public static String currentTraceId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? "" : traceId;
    }
}
