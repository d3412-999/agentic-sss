package org.spring_web.spring_boot_service.common;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.spring_web.spring_boot_service.config.SssProperties;
import org.springframework.stereotype.Component;

/**
 * 答辩题数规则：基础题 + 按源码评分分档加题，上限封顶。
 *
 * <p><b>为什么替换「代码评分 / 10 + 2」</b>：线性规则下 90 分要答 11 题，
 * 按每题 3 分钟算就是 33 分钟，学生后程必然敷衍；而 60 分只有 8 题，低分段区分度反而更差。
 * 分档之后「同一档位题数一致」，同分同量、可以对学生解释，也便于教师预估答辩时长。</p>
 *
 * <p><b>规则只在这里实现一次</b>：Service 用它出题，Controller 用它做发起前预览，
 * 前端通过 {@code GET /api/v1/teacher/defense/question-rule} 读同一份配置。
 * 任何一处硬编码题数都会让「预览 8 题、实际 9 题」这类问题重新出现。</p>
 *
 * <p>加分题不计入 {@link Plan#total()}，由 {@link Plan#bonusCount()} 单独表达；
 * 历史会话的 {@code question_count} 是落库快照，新规则不追溯修改。</p>
 */
@Slf4j
@Component
public class DefenseQuestionRule {

    /** 三原则维度（顺序即展示顺序）。 */
    public static final String P1 = "P1_RETELL";
    public static final String P2 = "P2_REASON";
    public static final String P3 = "P3_MODIFY";
    /** 加分题维度标记：与计分题同表，但 is_bonus=1 且不参与总分。 */
    public static final String BONUS = "IS_BONUS";

    private final SssProperties.QuestionRule config;
    private final SssProperties.Principle principle;

    public DefenseQuestionRule(SssProperties properties) {
        this.config = properties.getDefense().getQuestionRule();
        this.principle = properties.getDefense().getPrinciple();
    }

    /**
     * 题数计划。
     *
     * @param total       计分题总数（不含加分题）
     * @param tier        命中的档位 code；未命中任何档位为 {@code T0}
     * @param base        基础题数
     * @param added       档位加题数
     * @param bonusCount  加分题数量
     * @param easy        基础难度题数
     * @param medium      进阶难度题数
     * @param hard        挑战难度题数
     * @param bonusSeconds 加分题作答时限（秒）
     * @param bonusSkippable 加分题是否允许跳过
     */
    public record Plan(int total, String tier, int base, int added, int bonusCount,
                       int easy, int medium, int hard, int bonusSeconds, boolean bonusSkippable) {

        /** 前端预览用的「难度配比」文本。 */
        public String difficultyMixText() {
            return "基础 " + easy + " / 进阶 " + medium + " / 挑战 " + hard;
        }

        /** 落库快照，避免配置变更后无法还原当时的配比。 */
        public String difficultyMixSnapshot() {
            return "EASY:" + easy + ",MEDIUM:" + medium + ",HARD:" + hard;
        }
    }

    /**
     * 按源码评分算题数。{@code score} 为空（未评源码）时按 0 分档，只给基础题。
     *
     * <p>分数先夹到 0~100 再匹配档位：AI 返回越界分数已在评审阶段拒绝，
     * 这里再兜一次是为了不让一个脏数据把题数算成负数或 14 题。</p>
     */
    public Plan plan(BigDecimal score) {
        double value = score == null ? 0d : Math.max(0d, Math.min(100d, score.doubleValue()));
        SssProperties.Tier matched = null;
        for (SssProperties.Tier tier : config.getTiers()) {
            if (value >= tier.getMin() && value <= tier.getMax()) {
                matched = tier;
                break;
            }
        }
        int added = matched == null ? 0 : Math.max(0, matched.getAdd());
        String tierCode = matched == null ? "T0" : matched.getCode();
        int base = Math.max(1, config.getBaseCount());
        int total = Math.min(Math.max(base + added, base), Math.max(base, config.getMaxCount()));

        boolean highTier = added >= Math.max(1, config.getHighTierThreshold());
        int[] mix = normalizeMix(highTier ? config.getHighTierMix() : config.getBaseMix());
        int[] counts = distribute(total, mix);

        return new Plan(total, tierCode, base, added,
                Math.max(0, config.getBonusCount()),
                counts[0], counts[1], counts[2],
                Math.max(30, config.getBonusMaxAnswerSeconds()),
                config.isBonusSkippable());
    }

    /** 三原则判定阈值。 */
    public int passThreshold() {
        return principle.getThreshold();
    }

    /** 判定「完全依赖 AI」所需的未通过项数。 */
    public int failCountForAiDependent() {
        return Math.max(1, principle.getFailCountForAiDependent());
    }

    /** 需要覆盖的维度（按配置顺序，默认 P1/P2/P3）。 */
    public List<String> dimensions() {
        return List.copyOf(principle.getDimensions());
    }

    /** 给 AI 的维度说明：只给「考什么」，不给「这是第几原则」。 */
    public List<String> dimensionBriefs() {
        List<String> briefs = new ArrayList<>();
        for (String dimension : dimensions()) {
            briefs.add(switch (dimension) {
                case P1 -> "复述设计：能脱离代码与 AI，用自己的话说清关键模块的职责与数据流";
                case P2 -> "解释理由：能说明对现有实现（含 AI 生成内容）做过哪些修改、为什么这么改";
                case P3 -> "现场改动：能针对具体代码提出并口述一处小改动的完整做法与影响";
                default -> dimension;
            });
        }
        return briefs;
    }

    /** 题库维度是否合法（用于入参校验，防止前端传任意字符串）。 */
    public boolean isDimension(String dimension) {
        return dimensions().contains(dimension);
    }

    /** 配比归一化：非法值（负数 / 缺省 / 全 0）回退到 40/40/20。 */
    private int[] normalizeMix(List<Integer> mix) {
        if (mix == null || mix.size() != 3) {
            return new int[] {40, 40, 20};
        }
        int[] values = new int[3];
        int sum = 0;
        for (int i = 0; i < 3; i++) {
            Integer item = mix.get(i);
            values[i] = item == null ? 0 : Math.max(0, item);
            sum += values[i];
        }
        return sum == 0 ? new int[] {40, 40, 20} : values;
    }

    /**
     * 按百分比把 total 道题分到三档，用「最大余数法」保证求和恰好等于 total。
     *
     * <p>直接四舍五入会出现 6 题算成 2+2+2=6 的巧合，也会出现 7 题算成 3+3+2=8 的越界；
     * 最大余数法既不多也不少。</p>
     */
    private int[] distribute(int total, int[] mix) {
        int sum = mix[0] + mix[1] + mix[2];
        int[] counts = new int[3];
        double[] remainders = new double[3];
        int assigned = 0;
        for (int i = 0; i < 3; i++) {
            double exact = (double) total * mix[i] / sum;
            counts[i] = (int) Math.floor(exact);
            remainders[i] = exact - counts[i];
            assigned += counts[i];
        }
        List<Integer> order = new ArrayList<>(List.of(0, 1, 2));
        order.sort(Comparator.comparingDouble((Integer i) -> remainders[i]).reversed());
        int left = total - assigned;
        for (int i = 0; i < left; i++) {
            counts[order.get(i % 3)]++;
        }
        return counts;
    }
}
