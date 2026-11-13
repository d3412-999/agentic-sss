package org.spring_web.spring_boot_service.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.spring_web.spring_boot_service.config.SssProperties;

/**
 * 题数分档规则的单测。
 *
 * <p>这些断言就是「对学生可解释」的那份承诺：同一档位题数一致、上限不破、
 * 难度求和恰好等于总题数、加分题永远不计入 total。</p>
 */
class DefenseQuestionRuleTest {

    private final DefenseQuestionRule rule = new DefenseQuestionRule(new SssProperties());

    @Test
    void tiersFollowCodeScore() {
        assertEquals(6, rule.plan(score("0")).total());
        assertEquals(6, rule.plan(score("59.9")).total());
        assertEquals(7, rule.plan(score("60")).total());
        assertEquals(7, rule.plan(score("74")).total());
        assertEquals(8, rule.plan(score("75")).total());
        assertEquals(8, rule.plan(score("84")).total());
        assertEquals(9, rule.plan(score("85")).total());
        assertEquals(9, rule.plan(score("94")).total());
        assertEquals(10, rule.plan(score("95")).total());
        assertEquals(10, rule.plan(score("100")).total());
    }

    @Test
    void sameTierMeansSameCount() {
        assertEquals(rule.plan(score("85")).total(), rule.plan(score("94")).total());
        assertEquals(rule.plan(score("75")).tier(), rule.plan(score("84")).tier());
    }

    @Test
    void missingScoreFallsBackToBaseTier() {
        DefenseQuestionRule.Plan plan = rule.plan(null);
        assertEquals(6, plan.total());
        assertEquals("T0", plan.tier());
        assertEquals(0, plan.added());
    }

    @Test
    void outOfRangeScoreIsClampedInsteadOfOverflowing() {
        // 越界分数不该算出 14 题或负数题：上限就是 maxCount
        assertEquals(10, rule.plan(score("180")).total());
        assertEquals(6, rule.plan(score("-40")).total());
    }

    @Test
    void difficultyCountsSumToTotal() {
        for (int value = 0; value <= 100; value++) {
            DefenseQuestionRule.Plan plan = rule.plan(score(String.valueOf(value)));
            assertEquals(plan.total(), plan.easy() + plan.medium() + plan.hard(),
                    "难度配比求和必须等于总题数，score=" + value);
            assertTrue(plan.easy() >= 0 && plan.medium() >= 0 && plan.hard() >= 0);
        }
    }

    @Test
    void highTierRaisesChallengeShare() {
        DefenseQuestionRule.Plan base = rule.plan(score("60"));
        DefenseQuestionRule.Plan high = rule.plan(score("90"));
        double baseShare = (double) base.hard() / base.total();
        double highShare = (double) high.hard() / high.total();
        assertTrue(highShare >= baseShare, "高分段挑战题占比不应低于基础段");
    }

    @Test
    void bonusIsNotCountedInTotal() {
        DefenseQuestionRule.Plan plan = rule.plan(score("95"));
        assertEquals(10, plan.total());
        assertEquals(1, plan.bonusCount());
        assertEquals(120, plan.bonusSeconds());
        assertTrue(plan.bonusSkippable());
    }

    @Test
    void maxCountCapsEvenIfTiersAskForMore() {
        SssProperties properties = new SssProperties();
        properties.getDefense().getQuestionRule().setMaxCount(8);
        DefenseQuestionRule capped = new DefenseQuestionRule(properties);
        assertEquals(8, capped.plan(score("99")).total());
    }

    @Test
    void principleThresholdDefaultsToSixty() {
        assertEquals(60, rule.passThreshold());
        assertEquals(2, rule.failCountForAiDependent());
        assertEquals(3, rule.dimensions().size());
    }

    private BigDecimal score(String value) {
        return new BigDecimal(value);
    }
}
