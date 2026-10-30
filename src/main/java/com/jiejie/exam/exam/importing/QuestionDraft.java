package com.jiejie.exam.exam.importing;

/**
 * 导入过程中「一道题」的中间表示。
 *
 * <p>为什么不让解析器直接吐 {@code Question} 实体：
 * <ul>
 *   <li>解析阶段拿不到 {@code createdBy}（来自令牌），也不该在这里定；</li>
 *   <li>实体是可变 bean，一旦允许在校验前构造实体，就一定会有人在某个分支里
 *       先 {@code insert} 再说 —— fail-closed 靠类型守住比靠纪律守住可靠；</li>
 *   <li>{@code lineNo} 是报错定位的唯一凭据，实体里没有它的位置。</li>
 * </ul>
 *
 * <p>所有字段到这里已经过归一化：{@code type} 是 code 形式，{@code answer}
 * 是 {@code GradingRule#normalize} 之后的值（多选已排序），{@code options}
 * 是规范的 JSON 字符串，判断题的 {@code options} 一定是 {@code null}。
 */
public record QuestionDraft(
        /** 输入里的行号（从 1 开始），用于把错误指回具体那一条 */
        int lineNo,
        String subject,
        String type,
        String stem,
        String options,
        String answer,
        int score,
        int difficulty,
        String externalId,
        String source,
        String sourceRef,
        String license,
        /** 由 {@link ContentHash} 计算，构造时不允许调用方随便传 */
        String contentHash) {

    /** 报告里展示用的题干摘要：太长会把报告撑爆，截到 40 字符 */
    public String stemBrief() {
        if (stem == null) {
            return "";
        }
        return stem.length() <= 40 ? stem : stem.substring(0, 40) + "…";
    }
}
