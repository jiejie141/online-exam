package com.jiejie.exam.exam.importing;

import com.jiejie.exam.exam.QuestionSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JSONL 解析。
 *
 * <p>这里的每条用例都来自一个具体的现实来源：C-Eval 的答案表与题干分离、
 * MMLU 的选项是字符串数组、Excel 导出的选项是 {"A":"…"} 的 map、
 * 判断题在教辅里写成「正确/错误」、数字答案有的是 1 起有的是字母。
 *
 * <p>解析器的价值不在于"能读进来"，而在于<b>读不进来时能说清是第几行、哪里不对</b>。
 * 所以下面一半的用例断言的不是结果，而是错误信息的可定位性。
 */
class QuestionJsonlParserTest {

    private static final String OPTS_ABCD = "\"options\":[{\"key\":\"A\",\"text\":\"甲\"},"
            + "{\"key\":\"B\",\"text\":\"乙\"},{\"key\":\"C\",\"text\":\"丙\"},"
            + "{\"key\":\"D\",\"text\":\"丁\"}]";

    private QuestionJsonlParser parser() {
        return new QuestionJsonlParser(QuestionSource.CEVAL, 5, "CC BY-NC-SA 4.0");
    }

    private QuestionJsonlParser.ParseResult parse(String... lines) {
        return parser().parse(String.join("\n", lines));
    }

    // ---------------- 正常路径 ----------------

    @Test
    void 规范的一条题完整解析() {
        var r = parse("{\"subject\":\"Java\",\"type\":\"single\",\"stem\":\"String 可变吗？\","
                + OPTS_ABCD + ",\"answer\":\"B\",\"score\":10,\"difficulty\":2,"
                + "\"external_id\":\"java-17\",\"source\":\"ceval\","
                + "\"source_ref\":\"ceval v1.0 / 第 17 行\",\"license\":\"CC BY-NC-SA 4.0\"}");

        assertTrue(r.issues().isEmpty(), () -> r.issues().toString());
        assertEquals(1, r.drafts().size());
        QuestionDraft d = r.drafts().get(0);
        assertEquals(1, d.lineNo());
        assertEquals("Java", d.subject());
        assertEquals("single", d.type());
        assertEquals("B", d.answer());
        assertEquals(10, d.score());
        assertEquals(2, d.difficulty());
        assertEquals("java-17", d.externalId());
        assertEquals("ceval", d.source());
        assertEquals("CC BY-NC-SA 4.0", d.license());
        // 选项被重新序列化成规范形态
        assertTrue(d.options().contains("\"key\":\"A\""), d.options());
        assertEquals(64, d.contentHash().length(), "SHA-256 十六进制应为 64 字符");
    }

    @Test
    void 选项写成字符串数组时自动补ABC编号() {
        var r = parse("{\"subject\":\"数学\",\"type\":\"single\",\"stem\":\"1+1=?\","
                + "\"options\":[\"1\",\"2\",\"3\"],\"answer\":\"2\"}");
        assertTrue(r.issues().isEmpty(), () -> r.issues().toString());
        QuestionDraft d = r.drafts().get(0);
        // 答案 "2" 先按原始 key 查不到（原始 key 是 A/B/C），
        // 再按 1 起下标解释 -> 第二个选项 -> B
        assertEquals("B", d.answer());
        assertTrue(d.options().contains("\"key\":\"C\",\"text\":\"3\""), d.options());
    }

    @Test
    void 选项写成map形式也能读() {
        var r = parse("{\"subject\":\"数学\",\"type\":\"single\",\"stem\":\"1+1=?\","
                + "\"options\":{\"A\":\"1\",\"B\":\"2\"},\"answer\":\"B\"}");
        assertTrue(r.issues().isEmpty(), () -> r.issues().toString());
        assertEquals("B", r.drafts().get(0).answer());
    }

    @Test
    void 数字答案按1起下标映射() {
        // MMLU 的 CSV 里 answer 是 0..3，转换脚本会先转成字母再导；
        // 但直接从 1 起编号的题库也很常见，这里必须支持
        var r = parse("{\"subject\":\"物理\",\"type\":\"single\",\"stem\":\"单位是？\","
                + OPTS_ABCD + ",\"answer\":2}");
        assertTrue(r.issues().isEmpty(), () -> r.issues().toString());
        assertEquals("B", r.drafts().get(0).answer());
    }

    @Test
    void 原始的怪异选项key被重排成字母且答案跟着映射() {
        // 真实题库里见过用 1/2/3/4 当选项编号的。判分器按单字符比对答案，
        // 不重排的话这道题永远判不对
        var r = parse("{\"subject\":\"化学\",\"type\":\"single\",\"stem\":\"元素符号，氧是？\","
                + "\"options\":[{\"key\":\"1\",\"text\":\"O\"},{\"key\":\"2\",\"text\":\"H\"}],"
                + "\"answer\":\"1\"}");
        assertTrue(r.issues().isEmpty(), () -> r.issues().toString());
        assertEquals("A", r.drafts().get(0).answer(), "原始 key 1 -> 重排后 A");
    }

    @Test
    void 缺type时按形状推断() {
        var multi = parse("{\"subject\":\"历史\",\"type_missing\":1,\"stem\":\"下列正确的有\","
                + OPTS_ABCD + ",\"answer\":\"AC\"}");
        assertEquals("multiple", multi.drafts().get(0).type());
        assertTrue(multi.issues().isEmpty(), () -> multi.issues().toString());

        var judge = parse("{\"subject\":\"历史\",\"stem\":\"唐朝在前\",\"answer\":\"T\"}");
        assertTrue(judge.issues().isEmpty(), () -> judge.issues().toString());
        assertEquals("judge", judge.drafts().get(0).type());
    }

    @Test
    void 判断题的中文答案被映射成TF() {
        var r = parse("{\"subject\":\"常识\",\"type\":\"judge\",\"stem\":\"地球是圆的\","
                + "\"answer\":\"正确\"}");
        assertTrue(r.issues().isEmpty(), () -> r.issues().toString());
        assertEquals("T", r.drafts().get(0).answer());
        assertNull(r.drafts().get(0).options(), "判断题在库里 options 必须是 null");
    }

    @Test
    void 判断题带着正确错误两个选项时丢掉选项而不是报错() {
        // 教辅里判断题常写成 A.正确 B.错误。这不影响语义，直接丢
        var r = parse("{\"subject\":\"常识\",\"type\":\"judge\",\"stem\":\"地球是圆的\","
                + "\"options\":[\"正确\",\"错误\"],\"answer\":\"对\"}");
        assertTrue(r.issues().isEmpty(), () -> r.issues().toString());
        assertEquals("T", r.drafts().get(0).answer());
        assertNull(r.drafts().get(0).options());
    }

    @Test
    void BOM与注释行与空行都不影响行号() {
        // Excel「另存为」会带 BOM；Jackson 遇到 BOM 会报 "Unexpected character"
        // 且位置指向第一行第一列，看起来像数据错了 —— 排查一次半小时
        String text = "\uFEFF# 这是注释\n\n"
                + "{\"subject\":\"数学\",\"type\":\"single\",\"stem\":\"1+1=?\","
                + "\"options\":[\"1\",\"2\"],\"answer\":\"B\"}\n";
        var r = parser().parse(text);
        assertTrue(r.issues().isEmpty(), () -> r.issues().toString());
        assertEquals(1, r.drafts().size());
        assertEquals(3, r.drafts().get(0).lineNo(), "行号必须指向文件里的真实行，而不是第几条数据");
    }

    @Test
    void 同一道题写两个不同答案时指纹相同() {
        // 答案不进指纹 —— 这是"答案冲突"能被检出的前提。
        // 若答案参与哈希，两条会变成两道互不相干的题，各自入库
        var r = parse(
                "{\"subject\":\"数学\",\"type\":\"single\",\"stem\":\"1+1=?\","
                        + "\"options\":[\"1\",\"2\"],\"answer\":\"A\"}",
                "{\"subject\":\"数学\",\"type\":\"single\",\"stem\":\"1+1？\","
                        + "\"options\":[\"1\",\"2\"],\"answer\":\"B\"}");
        assertEquals(2, r.drafts().size());
        assertEquals(r.drafts().get(0).contentHash(), r.drafts().get(1).contentHash());
    }

    // ---------------- 坏数据：每一条都要能定位 ----------------

    @Test
    void 一行坏数据不影响其余行且行号准确() {
        var r = parse(
                "{\"subject\":\"数学\",\"type\":\"single\",\"stem\":\"1+1=?\",\"options\":[\"1\",\"2\"],\"answer\":\"B\"}",
                "{这不是 JSON",
                "{\"subject\":\"数学\",\"type\":\"single\",\"stem\":\"2+2=?\",\"options\":[\"3\",\"4\"],\"answer\":\"B\"}");

        assertEquals(2, r.drafts().size(), "好数据不该因为坏数据被丢掉");
        assertEquals(1, r.issues().size());
        assertEquals(2, r.issues().get(0).lineNo());
        assertTrue(r.issues().get(0).reason().contains("不是合法的 JSON"), r.issues().get(0).reason());
    }

    @Test
    void 答案下标0被拒绝而不是猜0起还是1起() {
        // 猜错的后果是一整场考试所有单选题都指向邻项，而且没有任何报错
        var r = parse("{\"subject\":\"数学\",\"type\":\"single\",\"stem\":\"1+1=?\","
                + OPTS_ABCD + ",\"answer\":0}");
        assertEquals(1, r.issues().size());
        assertTrue(r.issues().get(0).reason().contains("0 起还是 1 起"), r.issues().get(0).reason());
    }

    @Test
    void 答案不在选项里时错误信息说人话() {
        var r = parse("{\"subject\":\"数学\",\"type\":\"single\",\"stem\":\"1+1=?\","
                + OPTS_ABCD + ",\"answer\":\"Z\"}");
        assertEquals(1, r.issues().size());
        String reason = r.issues().get(0).reason();
        assertTrue(reason.contains("不在选项里"), reason);
    }

    @Test
    void 题干超长被拒而不是等数据库报错() {
        // 库里是 VARCHAR(1000)，超长会在 insert 时才炸，
        // 而 MySQL 报的 "Data too long" 不会告诉你是第几行
        String longStem = "题".repeat(1001);
        var r = parse("{\"subject\":\"语文\",\"type\":\"judge\",\"stem\":\"" + longStem + "\",\"answer\":\"T\"}");
        assertEquals(1, r.issues().size());
        assertTrue(r.issues().get(0).reason().contains("1000"), r.issues().get(0).reason());
    }

    @Test
    void 难度超出123被拒() {
        // 别的系统常用 1-5，4/5 会让"按难度抽题"永远抽不到，却看不出哪里错
        var r = parse("{\"subject\":\"语文\",\"type\":\"judge\",\"stem\":\"a\",\"answer\":\"T\",\"difficulty\":4}");
        assertEquals(1, r.issues().size());
        assertTrue(r.issues().get(0).reason().contains("1/2/3"), r.issues().get(0).reason());
    }

    @Test
    void 缺subject时报出字段名与别名() {
        var r = parse("{\"type\":\"judge\",\"stem\":\"a\",\"answer\":\"T\"}");
        assertEquals(1, r.issues().size());
        assertTrue(r.issues().get(0).reason().contains("subject"), r.issues().get(0).reason());
    }

    @Test
    void 判断题带真实选项时拒绝而不是静默丢弃() {
        // 这种数据通常是选择题被错标成 judge。静默丢选项会让题干里的
        // 「A.…B.…」失去参照，学生看到四个没有标号的选项
        var r = parse("{\"subject\":\"语文\",\"type\":\"judge\",\"stem\":\"选出正确的一项\","
                + OPTS_ABCD + ",\"answer\":\"T\"}");
        assertEquals(1, r.issues().size());
        assertTrue(r.issues().get(0).reason().contains("判断题不该带选项"), r.issues().get(0).reason());
    }

    @Test
    void 空输入给出可执行的提示() {
        var r = parser().parse("   \n\n");
        assertFalse(r.issues().isEmpty());
        assertEquals(0, r.issues().get(0).lineNo());
        assertTrue(r.issues().get(0).reason().contains("每行一个 JSON"), r.issues().get(0).reason());
    }

    @Test
    void 一次超过五千条直接拒绝() {
        // 不是技术限制，是"出了问题能人工过一遍"的上限
        String one = "{\"subject\":\"数学\",\"type\":\"judge\",\"stem\":\"a\",\"answer\":\"T\"}";
        List<String> lines = java.util.Collections.nCopies(QuestionJsonlParser.MAX_LINES + 1, one);
        var r = parser().parse(String.join("\n", lines));
        assertEquals(QuestionJsonlParser.MAX_LINES, r.drafts().size());
        assertTrue(r.issues().stream().anyMatch(i -> i.reason().contains("分批导入")));
    }

    // ---------------- 仓库里的示例文件必须真的能用 ----------------

    /**
     * 示例文件是文档的一部分，也是别人第一次接触这个功能的入口。
     * 它一旦和解析器脱节（改了字段名、多了个全角引号），文档就变成谎话，
     * 而且没人会发现 —— 因为没人会去手动验证"示例能不能导进去"。
     * 所以让构建来验：示例解析不出 10 道题，这里就红。
     */
    @Test
    void 示例文件可以完整导入且指纹互不相同() throws Exception {
        String text = java.nio.file.Files.readString(
                java.nio.file.Path.of("docs/question-bank/sample.jsonl"));
        var r = parser().parse(text);

        assertTrue(r.issues().isEmpty(), () -> r.issues().toString());
        assertEquals(10, r.drafts().size());
        assertEquals(10, r.drafts().stream().map(QuestionDraft::contentHash).distinct().count(),
                "示例里不该有两道题算出同一个指纹");
        assertTrue(r.drafts().stream().anyMatch(d -> "multiple".equals(d.type())), "示例要覆盖多选");
        assertTrue(r.drafts().stream().anyMatch(d -> "judge".equals(d.type())), "示例要覆盖判断");
    }

    /** 坏数据示例要能复现出文档里承诺的那几类问题，否则文档描述的现场是假的 */
    @Test
    void 坏数据示例暴露出五类问题且只放行一条好数据() throws Exception {
        String text = java.nio.file.Files.readString(
                java.nio.file.Path.of("docs/question-bank/sample-broken.jsonl"));
        var r = parser().parse(text);

        assertEquals(1, r.drafts().size());
        assertEquals(5, r.issues().size(), () -> r.issues().toString());
        assertTrue(r.issues().stream().anyMatch(i -> i.reason().contains("不在选项里")));
        assertTrue(r.issues().stream().anyMatch(i -> i.reason().contains("判断题不该带选项")));
        assertTrue(r.issues().stream().anyMatch(i -> i.reason().contains("1/2/3")));
        assertTrue(r.issues().stream().anyMatch(i -> i.reason().contains("0 起还是 1 起")));
        assertTrue(r.issues().stream().anyMatch(i -> i.reason().contains("不是合法的 JSON")));
    }
}
