package org.spring_web.spring_boot_service.common;

/**
 * 业务断言：把散落在各处的 if-throw 收敛成语义明确的几个动作。
 *
 * <p>区分三类失败很重要：{@code found} 是资源不存在（40401），
 * {@code state} 是状态机不允许（40901），{@code rule} 是业务规则不满足（42201）。
 * 前端正是靠 code 决定「返回列表 / 刷新状态 / 展示具体原因」。</p>
 */
public final class BizAssert {

    private BizAssert() {
    }

    public static void check(boolean condition, ErrorCode errorCode, String message) {
        if (!condition) {
            throw new BizException(errorCode, message);
        }
    }

    public static void rule(boolean condition, String message) {
        check(condition, ErrorCode.BUSINESS_RULE, message);
    }

    public static void rule(boolean condition, String message, Object data) {
        if (!condition) {
            throw new BizException(ErrorCode.BUSINESS_RULE, message, data);
        }
    }

    public static void state(boolean condition, String message) {
        check(condition, ErrorCode.CONFLICT, message);
    }

    public static void forbidden(boolean condition, String message) {
        check(condition, ErrorCode.FORBIDDEN, message);
    }

    public static void param(boolean condition, String message) {
        check(condition, ErrorCode.PARAM_INVALID, message);
    }

    public static <T> T found(T value, String what) {
        if (value == null) {
            throw new BizException(ErrorCode.NOT_FOUND, what + "不存在");
        }
        return value;
    }

    /** 解析前端传来的字符串 ID，非法值统一按参数错误处理，而不是 500。 */
    public static Long id(String raw, String field) {
        param(raw != null && !raw.isBlank(), field + "不能为空");
        try {
            long value = Long.parseLong(raw.trim());
            param(value > 0, field + "不合法");
            return value;
        } catch (NumberFormatException ex) {
            throw new BizException(ErrorCode.PARAM_INVALID, field + "必须是数字字符串");
        }
    }

    /**
     * 宽松解析：非法或为空返回 null，不抛异常。
     *
     * <p>只读校验场景用——前端可能带着过期/伪造的 id 来问「这个文件还在吗」，
     * 回答「不在」比抛参数错误更符合语义，也避免前端把它当请求失败处理。</p>
     */
    public static Long parseIdOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            long value = Long.parseLong(raw.trim());
            return value > 0 ? value : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
