package org.spring_web.spring_boot_service.common;

import jakarta.validation.ConstraintViolationException;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理：业务异常按错误码返回，未预期异常统一兜底并记录日志。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Object>> handleBizException(BizException ex) {
        ErrorCode errorCode = ex.getErrorCode();
        Result<Object> body = Result.<Object>fail(errorCode, ex.getMessage(), ex.getData());
        return ResponseEntity.status(errorCode.getHttpStatus()).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Object>> handleValidationException(MethodArgumentNotValidException ex) {
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> Map.of(
                        "field", fieldError.getField(),
                        "message", fieldError.getDefaultMessage() == null
                                ? "参数不合法"
                                : fieldError.getDefaultMessage()))
                .toList();
        Result<Object> body = Result.<Object>fail(
                ErrorCode.PARAM_INVALID, ErrorCode.PARAM_INVALID.getMessage(), Map.of("errors", errors));
        return ResponseEntity.status(ErrorCode.PARAM_INVALID.getHttpStatus()).body(body);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Result<Object>> handleConstraintViolation(ConstraintViolationException ex) {
        Result<Object> body = Result.<Object>fail(
                ErrorCode.PARAM_INVALID, ex.getMessage(), null);
        return ResponseEntity.status(ErrorCode.PARAM_INVALID.getHttpStatus()).body(body);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Result<Object>> handleMissingParameter(MissingServletRequestParameterException ex) {
        Result<Object> body = Result.<Object>fail(
                ErrorCode.PARAM_INVALID, "缺少参数：" + ex.getParameterName(), null);
        return ResponseEntity.status(ErrorCode.PARAM_INVALID.getHttpStatus()).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Object>> handleUnexpectedException(Exception ex) {
        log.error("Unhandled exception, traceId={}", Result.currentTraceId(), ex);
        Result<Object> body = Result.fail(ErrorCode.INTERNAL_ERROR);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.getHttpStatus()).body(body);
    }
}
