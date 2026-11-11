package org.spring_web.spring_boot_service.common;

/**
 * CSV 单元格工具：统一加引号、转义双引号、null 输出空单元格、公式注入防护。
 *
 * <p>导出的 CSV 会被 Excel / WPS 直接打开，所以每个字段都必须过这里：</p>
 * <ul>
 *   <li>所有字段加双引号，内部 {@code "} 转义为 {@code ""}，含逗号 / 换行的内容不会串列；</li>
 *   <li>null 输出 {@code ""}——直接拼接会得到字符串 "null"，被当成真实数据；</li>
 *   <li>去掉前导空白后以 {@code = + - @} 开头的值前面补一个单引号，
 *       避免被表格软件当作公式执行（CSV / 公式注入）。</li>
 * </ul>
 */
public final class CsvUtil {

    private CsvUtil() {
    }

    /** 字符串单元格：null 输出 {@code ""}，公式注入前缀单引号，再整体加引号。 */
    public static String field(String value) {
        if (value == null) {
            return "\"\"";
        }
        String guarded = guardFormula(value);
        return "\"" + guarded.replace("\"", "\"\"") + "\"";
    }

    /** 对象单元格（日期等）：null 输出 {@code ""}，其余按 {@code String.valueOf} 输出。 */
    public static String field(Object value) {
        return value == null ? "\"\"" : field(String.valueOf(value));
    }

    /** 数字单元格：null 输出 {@code ""}，其余按 plain string 输出（不出现 null / 科学计数法兜底）。 */
    public static String field(Number value) {
        return value == null ? "\"\"" : field(String.valueOf(value));
    }

    /** 公式注入防护：trim 后首字符是 = + - @ 时补单引号，让表格软件按文本处理。 */
    private static String guardFormula(String value) {
        String trimmed = value.strip();
        if (!trimmed.isEmpty()) {
            char first = trimmed.charAt(0);
            if (first == '=' || first == '+' || first == '-' || first == '@') {
                return "'" + value;
            }
        }
        return value;
    }
}
