package org.spring_web.spring_boot_service.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * AI 返回分数的解析与区间校验。
 *
 * <p>教育系统里「来路不明的分数比暂时没有结果更危险」，所以这里只做三件事：
 * 把上游返回值解析成数字、判断是否落在 0~100、统一保留一位小数；
 * <b>绝不</b>把越界分数悄悄夹到合法区间，越界就是契约被破坏，必须让调用方失败。</p>
 *
 * <p>{@link #clamp(BigDecimal)} 只给「占位分」这类明确允许兜底的场景使用，
 * 真实评分链路不应拿它掩盖错误。本类只依赖 {@code java.*}，可离线单测。</p>
 */
public final class ScoreGuard {

    /** 满分。 */
    private static final BigDecimal MAX_SCORE = BigDecimal.valueOf(100);
    /** 最低分。 */
    private static final BigDecimal MIN_SCORE = BigDecimal.ZERO;

    private ScoreGuard() {
    }

    /**
     * 解析上游分数：兼容数字与字符串，去掉首尾空白。
     *
     * @param raw 原始值（可能为 null、字符串、Integer / Double / BigDecimal 等）
     * @return 解析结果；null、空串、非数字（含 NaN / Infinity）一律返回 null
     */
    public static BigDecimal parse(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof BigDecimal decimal) {
            return decimal;
        }
        String text = raw.toString().trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** 是否落在闭区间 [0, 100]；null 视为不合法。 */
    public static boolean inRange(BigDecimal score) {
        return score != null
                && score.compareTo(MIN_SCORE) >= 0
                && score.compareTo(MAX_SCORE) <= 0;
    }

    /**
     * 兜底取值：null 按 0.0 处理，越界值夹到 [0, 100]，统一保留一位小数。
     *
     * <p>只用于「必须有值、但不代表真实评分」的占位场景；真实分数请先 {@link #inRange} 校验。</p>
     */
    public static BigDecimal clamp(BigDecimal score) {
        if (score == null || score.compareTo(MIN_SCORE) < 0) {
            return oneDecimal(MIN_SCORE);
        }
        if (score.compareTo(MAX_SCORE) > 0) {
            return oneDecimal(MAX_SCORE);
        }
        return oneDecimal(score);
    }

    /** 统一保留一位小数（四舍五入）；null 原样返回 null。 */
    public static BigDecimal oneDecimal(BigDecimal score) {
        return score == null ? null : score.setScale(1, RoundingMode.HALF_UP);
    }
}
