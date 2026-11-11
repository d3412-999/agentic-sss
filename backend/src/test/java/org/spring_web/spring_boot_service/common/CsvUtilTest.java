package org.spring_web.spring_boot_service.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/**
 * {@link CsvUtil} 的纯逻辑测试：不启动 Spring。
 *
 * <p>覆盖「所有字段加引号 / null 输出空单元格 / 双引号转义 / 公式注入前缀」四类行为。</p>
 */
class CsvUtilTest {

    @Test
    void fieldShouldQuoteAndEscape() {
        assertEquals("\"abc\"", CsvUtil.field("abc"));
        // 内部双引号写成两个双引号
        assertEquals("\"a\"\"b\"", CsvUtil.field("a\"b"));
        // 逗号 / 换行由外层引号保护，不会串列
        assertEquals("\"a,b\"", CsvUtil.field("a,b"));
        assertEquals("\"a\nb\"", CsvUtil.field("a\nb"));
    }

    @Test
    void fieldShouldRenderNullAsEmptyCell() {
        assertEquals("\"\"", CsvUtil.field((String) null));
        assertEquals("\"\"", CsvUtil.field((Number) null));
        assertEquals("\"\"", CsvUtil.field((Object) null));
        assertEquals("\"\"", CsvUtil.field(""));
    }

    @Test
    void fieldShouldGuardFormulaInjection() {
        assertEquals("\"'=1+1\"", CsvUtil.field("=1+1"));
        assertEquals("\"'+SUM(A1:A2)\"", CsvUtil.field("+SUM(A1:A2)"));
        assertEquals("\"'-1\"", CsvUtil.field("-1"));
        assertEquals("\"'@SUM(A1)\"", CsvUtil.field("@SUM(A1)"));
        // 去掉前导空白后再判断，单引号补在原始值最前面
        assertEquals("\"' =1\"", CsvUtil.field(" =1"));
        // 不触发前缀时保持原样
        assertEquals("\"a=1\"", CsvUtil.field("a=1"));
        assertEquals("\"1+1\"", CsvUtil.field("1+1"));
    }

    @Test
    void fieldShouldSupportNumberAndObjectColumns() {
        assertEquals("\"42\"", CsvUtil.field(42));
        assertEquals("\"87.50\"", CsvUtil.field(new BigDecimal("87.50")));
        // 负数属于 = + - @ 前缀规则，会被当文本（成绩单里分数已夹到 0~100，不会出现负数）
        assertEquals("\"'-12\"", CsvUtil.field(-12));
        assertEquals("\"42\"", CsvUtil.field((Object) 42));
        assertEquals("\"x\"", CsvUtil.field((Object) "x"));
        // 时间等对象按 toString 输出
        assertEquals("\"2026-01-02T03:04:05\"", CsvUtil.field((Object)
                LocalDateTime.of(2026, 1, 2, 3, 4, 5)));
    }
}
