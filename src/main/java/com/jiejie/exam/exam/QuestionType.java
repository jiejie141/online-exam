package com.jiejie.exam.exam;

/**
 * 题型。存库时用 code，避免用 ordinal —— 枚举顺序一变，历史数据就全错了。
 */
public enum QuestionType {

    /** 单选：答案形如 "A" */
    SINGLE("single"),
    /** 多选：答案形如 "ABD"，顺序无关 */
    MULTIPLE("multiple"),
    /** 判断：答案 "T" 或 "F" */
    JUDGE("judge");

    private final String code;

    QuestionType(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static QuestionType of(String code) {
        for (QuestionType t : values()) {
            if (t.code.equals(code)) {
                return t;
            }
        }
        throw new IllegalArgumentException("unknown question type: " + code);
    }
}
