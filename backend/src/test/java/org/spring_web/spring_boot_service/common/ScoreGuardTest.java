package org.spring_web.spring_boot_service.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** ScoreGuard 的纯函数测试：解析、区间判断、兜底夹取、一位小数。 */
class ScoreGuardTest {

    @Test
    void parseHandlesNullAndNonNumeric() {
        assertNull(ScoreGuard.parse(null));
        assertNull(ScoreGuard.parse("abc"));
        assertNull(ScoreGuard.parse("   "));
        assertNull(ScoreGuard.parse(Double.NaN));
    }

    @Test
    void parseAcceptsStringsAndNumbers() {
        assertEquals(new BigDecimal("87.45"), ScoreGuard.parse("87.45"));
        assertEquals(new BigDecimal("87.45"), ScoreGuard.parse(87.45d));
        assertEquals(new BigDecimal("87"), ScoreGuard.parse(87));
        assertEquals(new BigDecimal("12.5"), ScoreGuard.parse(" 12.5 "));
    }

    @Test
    void inRangeUsesClosedInterval() {
        assertFalse(ScoreGuard.inRange(null));
        assertFalse(ScoreGuard.inRange(new BigDecimal("-0.1")));
        assertFalse(ScoreGuard.inRange(new BigDecimal("100.1")));
        assertTrue(ScoreGuard.inRange(BigDecimal.ZERO));
        assertTrue(ScoreGuard.inRange(new BigDecimal("100")));
        assertTrue(ScoreGuard.inRange(new BigDecimal("87.45")));
    }

    @Test
    void clampFallsBackAndCaps() {
        assertEquals(new BigDecimal("0.0"), ScoreGuard.clamp(null));
        assertEquals(new BigDecimal("0.0"), ScoreGuard.clamp(new BigDecimal("-5")));
        assertEquals(new BigDecimal("100.0"), ScoreGuard.clamp(new BigDecimal("150")));
        assertEquals(new BigDecimal("42.3"), ScoreGuard.clamp(new BigDecimal("42.34")));
        assertEquals(new BigDecimal("42.4"), ScoreGuard.clamp(new BigDecimal("42.35")));
    }

    @Test
    void oneDecimalRoundsHalfUp() {
        assertNull(ScoreGuard.oneDecimal(null));
        assertEquals(new BigDecimal("87.5"), ScoreGuard.oneDecimal(new BigDecimal("87.45")));
        assertEquals(new BigDecimal("87.4"), ScoreGuard.oneDecimal(new BigDecimal("87.44")));
        assertEquals(new BigDecimal("100.0"), ScoreGuard.oneDecimal(new BigDecimal("100")));
    }
}
