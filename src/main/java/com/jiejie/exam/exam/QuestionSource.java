package com.jiejie.exam.exam;

/**
 * 题目来源。存库用 short code，理由是同一件事：用 ordinal 排序一变历史数据就全错。
 *
 * <p>把来源枚举化而不是让导入接口随便收一个字符串，是因为 {@code question.source}
 * 这一列会被用来做两件事，两件都要求取值封闭：
 * <ul>
 *   <li><b>版权盘查</b>：要能一条 SQL 查出"所有非自建的题"。取值开放的话，
 *       少一个字母（{@code C-Eval} / {@code ceval} / {@code Ceval}）就漏掉一批。</li>
 *   <li><b>重新导入时按来源覆盖</b>：将来要支持"把某数据集的题整体替换成新版本"，
 *       前提是上一批题的来源能精确匹配。</li>
 * </ul>
 *
 * <p>新增一个来源就在这里加一行，同时把它的版权约束写进
 * {@code docs/QUESTION-BANK-INGEST.md} —— 只加枚举不写许可证，等于给自己埋雷。
 */
public enum QuestionSource {

    /** 老师在页面上手敲的题。没有出处也没有许可证，但版权归自己 */
    MANUAL("manual"),
    /** C-Eval（中文，52 学科，CC BY-NC-SA 4.0） */
    CEVAL("ceval"),
    /** CMMLU（中文，67 学科，CC BY-NC-SA 4.0） */
    CMMLU("cmmlu"),
    /** MMLU（英文，MIT） */
    MMLU("mmlu"),
    /** 老师从 Excel/CSV 批量导入 */
    EXCEL("excel"),
    /** 院系授权题库 */
    SCHOOL("school");

    private final String code;

    QuestionSource(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    /**
     * 解析来源；空值当 MANUAL（历史数据的默认值就是 manual）。
     *
     * <p>这里不回退到 MANUAL 之外做过多的宽容（比如接受 "C-Eval"、"CEVAL"）——
     * 宽容会让"拼错的来源"被悄悄记成一个合法来源，等到版权盘查时才发现漏了。
     * 大小写严格匹配，报错信息里把合法值列全，拼错的人一眼能改。
     */
    public static QuestionSource of(String code) {
        if (code == null || code.isBlank()) {
            return MANUAL;
        }
        String trimmed = code.trim();
        for (QuestionSource s : values()) {
            if (s.code.equals(trimmed)) {
                return s;
            }
        }
        throw new IllegalArgumentException("未知题目来源: " + code
                + "，合法值: manual / ceval / cmmlu / mmlu / excel / school");
    }
}
