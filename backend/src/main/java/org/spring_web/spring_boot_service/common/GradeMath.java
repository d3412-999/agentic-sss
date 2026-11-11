package org.spring_web.spring_boot_service.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 成绩计算纯函数：权重折算、平均分、等级、区间夹取。
 *
 * <p>成绩是全系统「最不能被质疑」的一块，所以把规则抽成无状态静态方法：
 * 只依赖 JDK（{@code java.*}），可以脱离 Spring / 数据库直接单元测试。
 * 全程使用 BigDecimal，最后一步才 HALF_UP 保留 1 位小数，避免 float/double
 * 造成 87.45 变 87.4 这类争议。</p>
 *
 * <pre>
 * final = (答辩 × wDefense + 源码 × wCode + 报告 × wReport) / 100
 * 等级：≥90 优秀，≥80 良好，≥70 中等，≥60 及格，否则不及格
 * </pre>
 */
public final class GradeMath {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal ZERO_ONE = BigDecimal.ZERO.setScale(1);

    private GradeMath() {
    }

    /** null 按 0 分处理（缺考 / 未提交记 0）。 */
    public static BigDecimal nz(BigDecimal value) {
        return value == null ? ZERO_ONE : value;
    }

    /** 四舍五入保留 1 位小数；null 原样返回（表示「没有分数」而不是 0 分）。 */
    public static BigDecimal oneDecimal(BigDecimal value) {
        return value == null ? null : value.setScale(1, RoundingMode.HALF_UP);
    }

    /**
     * 加权总评：(答辩 × wDefense + 源码 × wCode + 报告 × wReport) / 100。
     *
     * <p>权重由开课配置保证和为 100；任一分项为 null 时按 0 计入。
     * 先乘后加再做唯一一次除法，避免每一步都舍入带来的累计误差。</p>
     */
    public static BigDecimal weighted(int wDefense, int wCode, int wReport,
                                      BigDecimal defense, BigDecimal code, BigDecimal report) {
        BigDecimal total = nz(defense).multiply(BigDecimal.valueOf(wDefense))
                .add(nz(code).multiply(BigDecimal.valueOf(wCode)))
                .add(nz(report).multiply(BigDecimal.valueOf(wReport)));
        return total.divide(HUNDRED, 1, RoundingMode.HALF_UP);
    }

    /** 算术平均：忽略 null；空列表或全部为 null 时返回 0.0（1 位小数）。 */
    public static BigDecimal average(List<BigDecimal> values) {
        if (values == null || values.isEmpty()) {
            return ZERO_ONE;
        }
        BigDecimal sum = BigDecimal.ZERO;
        int count = 0;
        for (BigDecimal value : values) {
            if (value != null) {
                sum = sum.add(value);
                count++;
            }
        }
        if (count == 0) {
            return ZERO_ONE;
        }
        return sum.divide(BigDecimal.valueOf(count), 1, RoundingMode.HALF_UP);
    }

    /** 等级：≥90 优秀 / ≥80 良好 / ≥70 中等 / ≥60 及格 / 其他（含 null）不及格。 */
    public static String level(BigDecimal score) {
        BigDecimal value = nz(score);
        if (value.compareTo(BigDecimal.valueOf(90)) >= 0) {
            return "优秀";
        }
        if (value.compareTo(BigDecimal.valueOf(80)) >= 0) {
            return "良好";
        }
        if (value.compareTo(BigDecimal.valueOf(70)) >= 0) {
            return "中等";
        }
        if (value.compareTo(BigDecimal.valueOf(60)) >= 0) {
            return "及格";
        }
        return "不及格";
    }

    /**
     * 把分数夹到 [min, max]，用于入库前的防御性兜底。
     *
     * <p>null 分数保持 null（表示缺考而不是 0 分）；min / max 允许为 null，
     * 表示该侧不限制。</p>
     */
    public static BigDecimal clamp(BigDecimal value, BigDecimal min, BigDecimal max) {
        if (value == null) {
            return null;
        }
        BigDecimal result = value;
        if (min != null && result.compareTo(min) < 0) {
            result = min;
        }
        if (max != null && result.compareTo(max) > 0) {
            result = max;
        }
        return result;
    }
}
