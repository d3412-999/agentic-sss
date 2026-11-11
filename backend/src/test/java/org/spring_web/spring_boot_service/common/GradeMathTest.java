package org.spring_web.spring_boot_service.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link GradeMath} 的纯逻辑测试：不启动 Spring，也不需要数据库。
 *
 * <p>覆盖权重折算、null 记 0、HALF_UP 舍入、等级边界、平均分与夹取。</p>
 */
class GradeMathTest {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    @Test
    void weightedShouldApplyWeightsOverHundred() {
        // 90×40 + 80×30 + 70×30 = 8100 → 81.0
        assertEquals(new BigDecimal("81.0"), GradeMath.weighted(40, 30, 30,
                new BigDecimal("90"), new BigDecimal("80"), new BigDecimal("70")));
    }

    @Test
    void weightedShouldTreatNullComponentAsZero() {
        assertEquals(new BigDecimal("0.0"), GradeMath.weighted(40, 30, 30, null, null, null));
        // 90×40 + 0 + 80×30 = 6000 → 60.0（源码缺考记 0）
        assertEquals(new BigDecimal("60.0"), GradeMath.weighted(40, 30, 30,
                new BigDecimal("90"), null, new BigDecimal("80")));
    }

    @Test
    void weightedShouldRoundHalfUpToSingleDecimal() {
        // 87.45 × 100 / 100 = 87.45 → 87.5（HALF_UP），不能因 double 精度变成 87.4
        assertEquals(new BigDecimal("87.5"), GradeMath.weighted(100, 0, 0,
                new BigDecimal("87.45"), null, null));
        // 0.5 的边界同样向上进位
        assertEquals(new BigDecimal("0.5"), GradeMath.weighted(100, 0, 0,
                new BigDecimal("0.5"), null, null));
    }

    @Test
    void averageShouldIgnoreNullsAndReturnOneDecimal() {
        // (90 + 80.5 + 70) / 3 = 80.1666... → 80.2
        List<BigDecimal> values = Arrays.asList(new BigDecimal("90"), null,
                new BigDecimal("80.5"), new BigDecimal("70"));
        assertEquals(new BigDecimal("80.2"), GradeMath.average(values));
    }

    @Test
    void averageShouldReturnZeroForEmptyOrAllNull() {
        assertEquals(new BigDecimal("0.0"), GradeMath.average(List.of()));
        assertEquals(new BigDecimal("0.0"), GradeMath.average(null));
        assertEquals(new BigDecimal("0.0"), GradeMath.average(Arrays.asList(null, null)));
        assertEquals(1, GradeMath.average(List.of()).scale());
    }

    @Test
    void levelShouldFollowGradeBoundaries() {
        assertEquals("优秀", GradeMath.level(new BigDecimal("100")));
        assertEquals("优秀", GradeMath.level(new BigDecimal("90")));
        assertEquals("良好", GradeMath.level(new BigDecimal("89.9")));
        assertEquals("良好", GradeMath.level(new BigDecimal("80")));
        assertEquals("中等", GradeMath.level(new BigDecimal("79.9")));
        assertEquals("中等", GradeMath.level(new BigDecimal("70")));
        assertEquals("及格", GradeMath.level(new BigDecimal("69.9")));
        assertEquals("及格", GradeMath.level(new BigDecimal("60")));
        assertEquals("不及格", GradeMath.level(new BigDecimal("59.9")));
        assertEquals("不及格", GradeMath.level(BigDecimal.ZERO));
        assertEquals("不及格", GradeMath.level(null));
    }

    @Test
    void clampShouldBoundBothSides() {
        assertEquals(new BigDecimal("0"), GradeMath.clamp(new BigDecimal("-5"),
                BigDecimal.ZERO, HUNDRED));
        assertEquals(new BigDecimal("100"), GradeMath.clamp(new BigDecimal("105"),
                BigDecimal.ZERO, HUNDRED));
        assertEquals(new BigDecimal("60"), GradeMath.clamp(new BigDecimal("60"),
                BigDecimal.ZERO, HUNDRED));
        assertEquals(new BigDecimal("-5"), GradeMath.clamp(new BigDecimal("-5"), null, HUNDRED));
        assertEquals(new BigDecimal("100"), GradeMath.clamp(new BigDecimal("120"), null, HUNDRED));
        assertEquals(new BigDecimal("0"), GradeMath.clamp(new BigDecimal("-1"), BigDecimal.ZERO, null));
        assertNull(GradeMath.clamp(null, BigDecimal.ZERO, HUNDRED));
    }

    @Test
    void oneDecimalShouldRoundHalfUpLikeARealScore() {
        assertEquals(new BigDecimal("87.4"), GradeMath.oneDecimal(new BigDecimal("87.44")));
        assertEquals(new BigDecimal("87.5"), GradeMath.oneDecimal(new BigDecimal("87.45")));
        assertEquals(new BigDecimal("87.3"), GradeMath.oneDecimal(new BigDecimal("87.25")));
        assertEquals(new BigDecimal("-87.3"), GradeMath.oneDecimal(new BigDecimal("-87.25")));
        assertNull(GradeMath.oneDecimal(null));
    }

    @Test
    void nzShouldReplaceNullWithZeroButKeepValues() {
        assertEquals(new BigDecimal("0.0"), GradeMath.nz(null));
        assertEquals(new BigDecimal("85"), GradeMath.nz(new BigDecimal("85")));
        assertEquals(new BigDecimal("0.00"), GradeMath.nz(new BigDecimal("0.00")));
    }
}
