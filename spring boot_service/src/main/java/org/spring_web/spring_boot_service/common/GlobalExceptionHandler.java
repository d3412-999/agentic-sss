package org.spring_web.spring_boot_service.common;

import jakarta.validation.ConstraintViolationException;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * 全局异常处理：所有失败都收敛为 {@code {code,message,data,traceId}}。
 *
 * <p>关键映射：唯一键冲突 → 40902（并发兜底），越权 → 40301，
 * 未预期异常 → 50000 且只记日志不外泄堆栈。</p>
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
                                ? "参数不合法" : fieldError.getDefaultMessage()))
                .toList();
        return build(ErrorCode.PARAM_INVALID, Map.of("errors", errors));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Result<Object>> handleConstraintViolation(ConstraintViolationException ex) {
        return build(ErrorCode.PARAM_INVALID, null);
    }

    @ExceptionHandler({BindException.class, MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class,
            IllegalArgumentException.class})
    public ResponseEntity<Result<Object>> handleBadRequest(Exception ex) {
        return build(ErrorCode.PARAM_INVALID, null);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Result<Object>> handleTooLarge(MaxUploadSizeExceededException ex) {
        return build(ErrorCode.PAYLOAD_TOO_LARGE, null);
    }

    /** 数据库唯一索引是并发最后一道防线，转换为「重复操作」而不是 500。 */
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<Result<Object>> handleDuplicate(DuplicateKeyException ex) {
        log.warn("Duplicate key, traceId={}, cause={}", Result.currentTraceId(), ex.getMessage());
        return build(ErrorCode.DUPLICATE_OPERATION, null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Result<Object>> handleAccessDenied(AccessDeniedException ex) {
        return build(ErrorCode.FORBIDDEN, null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Object>> handleUnexpectedException(Exception ex) {
        log.error("Unhandled exception, traceId={}", Result.currentTraceId(), ex);
        return build(ErrorCode.INTERNAL_ERROR, null);
    }

    private ResponseEntity<Result<Object>> build(ErrorCode errorCode, Object data) {
        Result<Object> body = Result.fail(errorCode, errorCode.getMessage(), data);
        return ResponseEntity.status(errorCode.getHttpStatus()).body(body);
    }
}
