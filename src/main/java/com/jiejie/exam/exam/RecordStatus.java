package com.jiejie.exam.exam;

/** 答卷状态机：答题中 -> 已交卷 -> 已判分。单向，不允许回退。 */
public enum RecordStatus {

    ONGOING("ongoing"),
    SUBMITTED("submitted"),
    /** 终态 */
    GRADED("graded");

    private final String code;

    RecordStatus(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public boolean canTransferTo(RecordStatus next) {
        return switch (this) {
            case ONGOING -> next == SUBMITTED;
            case SUBMITTED -> next == GRADED;
            case GRADED -> false;
        };
    }

    public static RecordStatus of(String code) {
        for (RecordStatus s : values()) {
            if (s.code.equals(code)) {
                return s;
            }
        }
        throw new IllegalArgumentException("unknown record status: " + code);
    }
}
