package com.jiejie.exam.web;

/** 业务规则被违反时抛出。交给 GlobalExceptionHandler 转成 400，而不是 500。 */
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(String message) {
        this(400, message);
    }

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int code() {
        return code;
    }
}
