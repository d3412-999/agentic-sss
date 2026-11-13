package org.spring_web.spring_boot_service.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** 难度字典：写入严格、读取兜底，任何历史脏值都不能让读取报错。 */
class DifficultyTest {

    @Test
    void requireDefaultsToMediumWhenBlank() {
        assertEquals("MEDIUM", Difficulty.require(null));
        assertEquals("MEDIUM", Difficulty.require("  "));
    }

    @Test
    void requireAcceptsCodeAndChineseLabel() {
        assertEquals("EASY", Difficulty.require("easy"));
        assertEquals("HARD", Difficulty.require("挑战"));
        assertEquals("MEDIUM", Difficulty.require(" MEDIUM "));
    }

    @Test
    void requireRejectsUnknownValue() {
        BizException ex = org.junit.jupiter.api.Assertions.assertThrows(
                BizException.class, () -> Difficulty.require("超级难"));
        assertEquals(ErrorCode.PARAM_INVALID, ex.getErrorCode());
    }

    @Test
    void labelOfFallsBackForHistoricalGarbage() {
        assertEquals("入门", Difficulty.labelOf("EASY"));
        assertEquals("挑战", Difficulty.labelOf("HARD"));
        assertEquals(Difficulty.UNKNOWN_LABEL, Difficulty.labelOf("BETA"));
        assertEquals(Difficulty.UNKNOWN_LABEL, Difficulty.labelOf(null));
    }

    @Test
    void filterCodeTreatsUnknownAsNoFilter() {
        assertNull(Difficulty.filterCode(null));
        assertNull(Difficulty.filterCode("all"));
        assertNull(Difficulty.filterCode("WRONG"));
        assertEquals("MEDIUM", Difficulty.filterCode("进阶"));
    }
}
