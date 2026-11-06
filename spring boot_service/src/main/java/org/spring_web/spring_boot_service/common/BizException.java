package org.spring_web.spring_boot_service.common;

import lombok.Getter;

/**
 * 业务异常：由 {@link GlobalExceptionHandler} 统一转换为标准响应。
 */
@Getter
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;
    private final transient Object data;

    public BizException(ErrorCode errorCode) {
        this(errorCode, errorCode.getMessage(), null);
    }

    public BizException(ErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    public BizException(ErrorCode errorCode, String message, Object data) {
        super(message);
        this.errorCode = errorCode;
        this.data = data;
    }
}
