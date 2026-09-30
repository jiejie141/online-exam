package com.jiejie.exam.exam;

import java.util.Set;

/** 考试状态机。草稿 -> 已发布 -> 已结束，只允许单向推进。 */
public enum ExamStatus {

    DRAFT("draft"),
    PUBLISHED("published"),
    /** 终态：时间窗过了或老师手动关闭 */
    CLOSED("closed");

    private final String code;

    ExamStatus(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public boolean canTransferTo(ExamStatus next) {
        return switch (this) {
            case DRAFT -> next == PUBLISHED || next == CLOSED;
            case PUBLISHED -> next == CLOSED;
            case CLOSED -> false;
        };
    }

    public static ExamStatus of(String code) {
        for (ExamStatus s : values()) {
            if (s.code.equals(code)) {
                return s;
            }
        }
        throw new IllegalArgumentException("unknown exam status: " + code);
    }

    /** 学生能进入的状态：只有已发布 */
    public static final Set<String> ENTERABLE = Set.of(PUBLISHED.code);
}
