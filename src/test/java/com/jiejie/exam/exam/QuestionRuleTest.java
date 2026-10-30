package com.jiejie.exam.exam;

import com.jiejie.exam.web.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「这道题到底能不能判对」的校验。
 *
 * <p>这些用例全都来自真实接口探针：下面每一种写法都曾经被接口 200 接收、
 * 一路入库、发布、让学生作答，然后全班 0 分，而且全程没有任何报错。
 * 判分算错是这类系统里最不可接受的 bug，而"永远判不对"比算错更糟 ——
 * 它连错都算不上，只是一个所有人都会输的坑。
 */
class QuestionRuleTest {

    private static final String OPTIONS_ABCD =
            "[{\"key\":\"A\",\"text\":\"甲\"},{\"key\":\"B\",\"text\":\"乙\"},"
            + "{\"key\":\"C\",\"text\":\"丙\"},{\"key\":\"D\",\"text\":\"丁\"}]";

    private static BusinessException reject(QuestionType type, String options, String answer) {
        return assertThrows(BusinessException.class, () -> QuestionRule.validate(type, options, answer));
    }

    // ---------------- 判断题 ----------------

    @Test
    void 判断题答案只能是T或F() {
        // 实测：answer="正确" 曾经 200 入库。判分时 normalize("正确") 是 "正确"，
        // 而学生端只会提交 T/F —— 这一题无论怎么答都是 0 分。
        BusinessException e = reject(QuestionType.JUDGE, null, "正确");
        assertEquals(400, e.code());
        assertTrue(e.getMessage().contains("T 或 F"), e.getMessage());
    }

    @Test
    void 判断题答案写成字母A也拒绝() {
        // 探针里这条也 200 入库了。A 不是 T/F，学生选不到。
        reject(QuestionType.JUDGE, null, "A");
    }

    @Test
    void 判断题答案小写t可以接受() {
        // 判分走 GradingRule.normalize，会统一转大写 —— 所以小写 t 是合法的，
        // 不能顺手把它也挡了，否则出题人用小写就被拒。
        assertDoesNotThrow(() -> QuestionRule.validate(QuestionType.JUDGE, null, "t"));
        assertDoesNotThrow(() -> QuestionRule.validate(QuestionType.JUDGE, null, " F "));
    }

    @Test
    void 判断题不该带选项() {
        // 判断题在库里 options 必须是 NULL，前端会补 T/F 两个选项。
        // 存一份进库只会让 PaperItem 多一个没人用的字段。
        BusinessException e = reject(QuestionType.JUDGE, OPTIONS_ABCD, "T");
        assertEquals(400, e.code());
    }

    // ---------------- 选择题 ----------------

    @Test
    void 答案不在选项里就拒绝() {
        // 最严重的一种：选项只有 A–D，正确答案却是 Z。
        // 实测学生把 A/B/C/D 全试了一遍，每种都是 0 分 —— 这道题无人可答对。
        BusinessException e = reject(QuestionType.SINGLE, OPTIONS_ABCD, "Z");
        assertEquals(400, e.code());
        assertTrue(e.getMessage().contains("Z"), e.getMessage());
        // 错误消息要能直接指出"学生永远选不到它"，而不是干巴巴一句"参数不合法"
        assertTrue(e.getMessage().contains("永远选不到"), e.getMessage());
    }

    @Test
    void 多选里只要有一个字母在选项外就拒绝() {
        BusinessException e = reject(QuestionType.MULTIPLE, OPTIONS_ABCD, "AE");
        assertEquals(400, e.code());
        assertTrue(e.getMessage().contains("E"), e.getMessage());
    }

    @Test
    void 单选只能有一个答案() {
        BusinessException e = reject(QuestionType.SINGLE, OPTIONS_ABCD, "AB");
        assertEquals(400, e.code());
        assertTrue(e.getMessage().contains("只能有一个"), e.getMessage());
    }

    @Test
    void 多选顺序与分隔符不影响校验() {
        // 判分侧 normalize 会去掉空格/逗号并排序，校验侧必须用同一套规则，
        // 否则 "B,A" 这种写法会在入库时被拒、判分时却认为合法。
        assertDoesNotThrow(() -> QuestionRule.validate(QuestionType.MULTIPLE, OPTIONS_ABCD, "B,A"));
        assertDoesNotThrow(() -> QuestionRule.validate(QuestionType.MULTIPLE, OPTIONS_ABCD, "d b"));
        assertDoesNotThrow(() -> QuestionRule.validate(QuestionType.SINGLE, OPTIONS_ABCD, " a "));
    }

    @Test
    void 选项不是合法JSON时回400而不是500() {
        // 实测：options="not-json" 曾经让 MySQL 抛 Data truncation，
        // 最后被兜底成 500「服务内部错误」。可这明明是调用方传错了格式。
        BusinessException e = reject(QuestionType.SINGLE, "not-json", "A");
        assertEquals(400, e.code());
        assertTrue(e.getMessage().contains("JSON"), e.getMessage());
    }

    @Test
    void 选项少于两个就拒绝() {
        // 一个选项的选择题没有意义：要么学生必对，要么必错。
        reject(QuestionType.SINGLE, "[]", "A");
        reject(QuestionType.SINGLE, "[{\"key\":\"A\",\"text\":\"甲\"}]", "A");
    }

    @Test
    void 选项key重复就拒绝() {
        // 两个都是 A 时，学生选 A 到底算选了哪一个？判分和界面都会歧义。
        reject(QuestionType.SINGLE,
                "[{\"key\":\"A\",\"text\":\"甲\"},{\"key\":\"A\",\"text\":\"乙\"}]", "A");
    }

    @Test
    void 选项缺key或内容为空就拒绝() {
        reject(QuestionType.SINGLE, "[{\"text\":\"甲\"},{\"text\":\"乙\"}]", "A");
        reject(QuestionType.SINGLE, "[{\"key\":\"A\",\"text\":\"\"},{\"key\":\"B\",\"text\":\"乙\"}]", "A");
    }

    @Test
    void 选择题没有选项就拒绝() {
        BusinessException e = reject(QuestionType.SINGLE, null, "A");
        assertEquals(400, e.code());
        reject(QuestionType.SINGLE, "   ", "A");
    }

    @Test
    void 答案为空就拒绝() {
        reject(QuestionType.SINGLE, OPTIONS_ABCD, "");
        reject(QuestionType.SINGLE, OPTIONS_ABCD, "   ");
    }

    @Test
    void 正常的选择题与判断题都通过() {
        // 反向断言：上面挡的都是坏输入，不能顺手把 seed.sql 里那 10 道真题也挡了。
        assertDoesNotThrow(() -> QuestionRule.validate(QuestionType.SINGLE, OPTIONS_ABCD, "B"));
        assertDoesNotThrow(() -> QuestionRule.validate(QuestionType.MULTIPLE, OPTIONS_ABCD, "ABCD"));
        assertDoesNotThrow(() -> QuestionRule.validate(QuestionType.JUDGE, null, "T"));
        assertDoesNotThrow(() -> QuestionRule.validate(QuestionType.JUDGE, null, "F"));
    }
}
