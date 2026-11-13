package org.spring_web.spring_boot_service.common;

import java.util.List;
import java.util.Locale;

/**
 * 题目难度字典：稳定编码 + 展示名 + 排序值。
 *
 * <p><b>为什么不用中文当存储值</b>：库里存 {@code EASY}，展示名可以随时改文案、做多语言，
 * 也能安全地新增等级（只需在枚举末尾追加一项），不会因为改文案而让历史数据变成非法值。</p>
 *
 * <p><b>读与写用两套语义</b>：写入时非法值直接拒绝（{@link #require}），避免脏数据；
 * 读取时非法值降级为「未分级」（{@link #labelOf}），保证历史数据永远不会读取报错。</p>
 */
public enum Difficulty {

    EASY("EASY", "入门", 10),
    MEDIUM("MEDIUM", "进阶", 20),
    HARD("HARD", "挑战", 30);

    /** 无法识别编码时的占位文案，只用于展示，不参与比较。 */
    public static final String UNKNOWN_LABEL = "未分级";

    private final String code;
    private final String label;
    private final int sort;

    Difficulty(String code, String label, int sort) {
        this.code = code;
        this.label = label;
        this.sort = sort;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    public int getSort() {
        return sort;
    }

    /** 按排序值返回全部等级，供 /meta/difficulties 下发前端渲染筛选项。 */
    public static List<Difficulty> ordered() {
        return List.of(values());
    }

    /**
     * 宽松解析：接受编码（大小写不敏感）或中文展示名，历史数据里两者都可能出现。
     * 无法识别返回 {@code null}，由调用方决定是拒绝还是兜底。
     */
    public static Difficulty parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        for (Difficulty item : values()) {
            if (item.code.equalsIgnoreCase(value) || item.label.equals(value)) {
                return item;
            }
        }
        return null;
    }

    /** 写入路径：空值走默认（进阶），非法值按参数错误拒绝，绝不把脏编码落库。 */
    public static String require(String raw) {
        if (raw == null || raw.isBlank()) {
            return MEDIUM.code;
        }
        Difficulty parsed = parse(raw);
        BizAssert.param(parsed != null, "难度取值不合法，仅支持 EASY / MEDIUM / HARD");
        return parsed.code;
    }

    /** 查询路径：把前端传来的难度过滤条件收敛成库里的编码，无效值视为「不筛选」。 */
    public static String filterCode(String raw) {
        if (raw == null || raw.isBlank() || "all".equalsIgnoreCase(raw)) {
            return null;
        }
        Difficulty parsed = parse(raw);
        return parsed == null ? null : parsed.code;
    }

    /** 展示路径：任何无法识别的历史值都不会抛异常，统一显示「未分级」。 */
    public static String labelOf(String code) {
        Difficulty parsed = parse(code);
        return parsed == null ? UNKNOWN_LABEL : parsed.label;
    }
}
