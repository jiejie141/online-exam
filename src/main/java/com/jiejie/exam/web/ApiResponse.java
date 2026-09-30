package com.jiejie.exam.web;

/** 统一响应包装。前端只需要判断 code，不用猜不同接口返回结构不一样。 */
public record ApiResponse<T>(int code, String message, T data) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "ok", data);
    }

    public static <T> ApiResponse<T> fail(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}
