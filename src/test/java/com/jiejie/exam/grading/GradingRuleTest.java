package com.jiejie.exam.grading;

import com.jiejie.exam.exam.QuestionType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 判分规则。
 *
 * <p>这里要钉住的是多选的三种结局（全对 / 漏选 / 错选）—— 它们的边界很像，
 * 写成"包含就加分"或"长度相等就满分"都会错，而且错了以后分数看起来也"合理"，
 * 不容易被发现。必须靠用例把每个分支固定住。
 */
class GradingRuleTest {

    private static final int FULL = 5;

    @Test
    void 单选答对得满分() {
        GradingRule.Result r = GradingRule.grade(QuestionType.SINGLE, "A", "A", FULL);
        assertTrue(r.correct());
        assertEquals(FULL, r.scoreGot());
    }

    @Test
    void 单选答错得零分() {
        GradingRule.Result r = GradingRule.grade(QuestionType.SINGLE, "A", "C", FULL);
        assertFalse(r.correct());
        assertEquals(0, r.scoreGot());
    }

    @Test
    void 判断题同样规则() {
        assertTrue(GradingRule.grade(QuestionType.JUDGE, "T", "T", FULL).correct());
        assertEquals(0, GradingRule.grade(QuestionType.JUDGE, "T", "F", FULL).scoreGot());
        // 学生端提交小写的 t 也要认
        assertTrue(GradingRule.grade(QuestionType.JUDGE, "T", "t", FULL).correct());
    }

    @Test
    void 多选全对得满分() {
        GradingRule.Result r = GradingRule.grade(QuestionType.MULTIPLE, "ABD", "ABD", FULL);
        assertTrue(r.correct());
        assertEquals(FULL, r.scoreGot());
    }

    @Test
    void 多选漏选给半分但不算对() {
        GradingRule.Result r = GradingRule.grade(QuestionType.MULTIPLE, "ABD", "AB", FULL);
        assertFalse(r.correct(), "漏选不应算作完全正确，否则正确率统计会虚高");
        assertEquals(2, r.scoreGot(), "5 分的题漏选给一半，向下取整为 2");
    }

    @Test
    void 多选错选得零分() {
        // 选了一个不在正确答案里的选项：整题不给分
        GradingRule.Result r = GradingRule.grade(QuestionType.MULTIPLE, "ABD", "ABC", FULL);
        assertFalse(r.correct());
        assertEquals(0, r.scoreGot());
    }

    @Test
    void 多选只选一个错项也是零分() {
        assertEquals(0, GradingRule.grade(QuestionType.MULTIPLE, "ABD", "C", FULL).scoreGot());
    }

    @Test
    void 多选顺序与分隔符都不影响判分() {
        for (String variant : new String[]{"ABD", "DBA", "b d a", "A,B,D", "A|B|D"}) {
            GradingRule.Result r = GradingRule.grade(QuestionType.MULTIPLE, "ABD", variant, FULL);
            assertTrue(r.correct(), "变体 " + variant + " 应判为全对");
            assertEquals(FULL, r.scoreGot());
        }
    }

    @Test
    void 未作答得零分且不抛异常() {
        for (String blank : new String[]{null, "", "   "}) {
            GradingRule.Result r = GradingRule.grade(QuestionType.SINGLE, "A", blank, FULL);
            assertFalse(r.correct());
            assertEquals(0, r.scoreGot());
        }
    }

    @Test
    void 题目未配答案属于数据错误必须暴露() {
        // 静默判 0 分会让所有学生都扣分且没人知道原因，宁可抛出来
        assertThrows(IllegalStateException.class,
                () -> GradingRule.grade(QuestionType.SINGLE, "  ", "A", FULL));
    }

    @Test
    void 负的满分属于调用错误() {
        assertThrows(IllegalArgumentException.class,
                () -> GradingRule.grade(QuestionType.SINGLE, "A", "A", -1));
    }

    @Test
    void 一半分向下取整不产生小数分() {
        // 3 分的多选漏选：3/2 = 1，而不是 1.5
        assertEquals(1, GradingRule.grade(QuestionType.MULTIPLE, "ABC", "AB", 3).scoreGot());
        // 1 分的多选漏选只能给 0
        assertEquals(0, GradingRule.grade(QuestionType.MULTIPLE, "ABC", "AB", 1).scoreGot());
    }

    @Test
    void 归一化把各种写法压成同一个答案() {
        assertEquals("ABD", GradingRule.normalize("d, b a"));
        assertEquals("T", GradingRule.normalize(" t "));
        // 重复选项去重
        assertEquals("AB", GradingRule.normalize("AAB"));
    }
}
