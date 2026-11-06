package org.spring_web.spring_boot_service.common;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 统一错误码。
 *
 * <p>与前端约定的结构：{@code code} 为业务码，0 表示成功；HTTP 状态码另行表达语义。</p>
 */
@Getter
public enum ErrorCode {

    SUCCESS(0, HttpStatus.OK, "ok"),

    PARAM_INVALID(40001, HttpStatus.BAD_REQUEST, "参数校验失败"),
    FILE_INVALID(40002, HttpStatus.BAD_REQUEST, "文件类型或大小不合法"),

    UNAUTHORIZED(40101, HttpStatus.UNAUTHORIZED, "未登录或登录已过期"),
    LOGIN_FAILED(40102, HttpStatus.UNAUTHORIZED, "账号或密码错误"),
    ACCOUNT_DISABLED(40103, HttpStatus.UNAUTHORIZED, "账号已被禁用"),

    FORBIDDEN(40301, HttpStatus.FORBIDDEN, "没有访问权限"),

    NOT_FOUND(40401, HttpStatus.NOT_FOUND, "资源不存在"),

    CONFLICT(40901, HttpStatus.CONFLICT, "资源状态已变更，请刷新后重试"),
    DUPLICATE_OPERATION(40902, HttpStatus.CONFLICT, "请勿重复操作"),

    PAYLOAD_TOO_LARGE(41301, HttpStatus.PAYLOAD_TOO_LARGE, "请求体过大"),

    BUSINESS_RULE(42201, HttpStatus.UNPROCESSABLE_ENTITY, "业务规则不满足"),
    MATERIAL_INCOMPLETE(42202, HttpStatus.UNPROCESSABLE_ENTITY, "材料不齐，无法继续"),

    TOO_MANY_REQUESTS(42901, HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁"),

    INTERNAL_ERROR(50000, HttpStatus.INTERNAL_SERVER_ERROR, "服务端异常"),
    AI_UNAVAILABLE(50301, HttpStatus.SERVICE_UNAVAILABLE, "AI 服务暂不可用");

    private final int code;
    private final HttpStatus httpStatus;
    private final String message;

    ErrorCode(int code, HttpStatus httpStatus, String message) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.message = message;
    }
}
