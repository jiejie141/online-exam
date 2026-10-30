package com.jiejie.exam.exam.importing;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 一次导入的结果。
 *
 * <p>这份报告是这个功能的核心，而不是附赠的日志。理由很实际：
 * 导入 5000 道题、进去 4997 道，剩下 3 道被拒 —— 如果只返回一个 "ok"，
 * 老师没有任何办法知道少了哪 3 道、为什么少。<b>没有定位能力的批量操作
 * 等于制造数据黑箱。</b>
 *
 * <p>四种结果必须分开计数，它们的处置方式完全不同：
 * <table border="1">
 *   <tr><th>字段</th><th>含义</th><th>该怎么做</th></tr>
 *   <tr><td>{@code inserted}</td><td>新入库</td><td>不用管</td></tr>
 *   <tr><td>{@code duplicate}</td><td>指纹已在库里，且答案一致</td><td>正常，重跑导入时全是这个</td></tr>
 *   <tr><td>{@code conflicts}</td><td>指纹已在库里，但<strong>答案不同</strong></td>
 *       <td><strong>必须人工裁决</strong>：同一道题被录了两个不同答案，其中一个一定是错的</td></tr>
 *   <tr><td>{@code issues}</td><td>校验不通过，整批未写库</td><td>改数据源再导</td></tr>
 * </table>
 *
 * <p>把 {@code duplicate} 和 {@code conflicts} 合成一个"跳过"是常见的偷懒，
 * 但那样会把最危险的情况藏起来：答案冲突是"已经有学生按错答案判过卷"的源头。
 * 冲突条目不阻断其余题目入库 —— 它们只是不能替你把答案改了。
 */
public record ImportReport(
        /** true = 只预演，一个字都没写库 */
        boolean dryRun,
        /** 非空行的总数（含被拒的） */
        int total,
        int inserted,
        int duplicate,
        /** 答案与库里已有数据不一致的条目明细 */
        List<Conflict> conflicts,
        /** 本次回填了历史数据的指纹条数（V4 之前的题 content_hash 为 NULL） */
        int backfilled,
        /** 校验失败明细，最多 50 条，超出部分用 {@link #issuesTruncated} 标记 */
        List<Issue> issues,
        boolean issuesTruncated,
        /** 抽样若干条待入库的题，供人工核对答案 —— 真实数据集里确有错题 */
        List<Sample> samples) {

    /** 一条校验失败。{@code lineNo} 为 0 表示不是某一行的错（例如整批超限） */
    public record Issue(int lineNo, String reason) {
    }

    /**
     * 答案冲突：同一道题（指纹相同）在库里已有记录，但答案不一样。
     *
     * <p>这个结构存在的唯一目的是让人能<b>当场</b>判断谁对谁错。
     * 只给一个计数是没用的 —— 老师不可能去 5000 道题里手找出那 3 道。
     */
    public record Conflict(int lineNo, String stem, String existingAnswer, String incomingAnswer) {
    }

    /** 抽样。只带结论字段，不带选项 JSON，报告要能直接贴进聊天窗口 */
    public record Sample(int lineNo, String subject, String type, String stem, String answer) {
    }

    /**
     * 可以安全落库的条件：没有任何一条校验失败。
     *
     * <p>加 {@code @JsonProperty} 是必须的，不是可选装饰：Jackson 序列化 record
     * 时只认**组件**，这个方法是派生出来的，不加注解就不会出现在 JSON 里。
     * 后果很隐蔽 —— 前端读 `report.clean` 拿到 undefined，
     * 于是"校验通过"永远显示成"有问题"，「确认导入」按钮永远点不动，
     * 而接口本身返回 200、日志里一片正常。
     */
    @JsonProperty("clean")
    public boolean clean() {
        return issues.isEmpty();
    }

    /**
     * 冲突条数（与 {@link #conflicts} 长度一致，单独给一个数是为了前端不必判空）。
     *
     * <p>同上：不加注解就不会序列化。
     */
    @JsonProperty("conflicting")
    public int conflicting() {
        return conflicts == null ? 0 : conflicts.size();
    }
}
