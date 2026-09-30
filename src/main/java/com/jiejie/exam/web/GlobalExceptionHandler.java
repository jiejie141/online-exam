package com.jiejie.exam.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 把异常翻译成统一结构。业务异常 -> 400，其余 -> 500，不允许把堆栈直接吐给前端。 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 业务异常的状态码要跟着 e.code() 走，不能一律 400。
     *
     * <p>一开始这里写死 @ResponseStatus(BAD_REQUEST)，结果登录失败时
     * HTTP 状态是 400、body 里的 code 却是 401 —— 客户端到底该看哪个？
     * 只判断 HTTP 状态的调用方会把"口令错"当成"参数错"处理。
     * 统一由 code 决定状态码，两边说同一件事。
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e) {
        HttpStatus status = switch (e.code()) {
            case 401 -> HttpStatus.UNAUTHORIZED;
            case 403 -> HttpStatus.FORBIDDEN;
            case 404 -> HttpStatus.NOT_FOUND;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(ApiResponse.fail(e.code(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(err -> err.getField() + " " + err.getDefaultMessage())
                .orElse("参数不合法");
        return ApiResponse.fail(400, msg);
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleUnexpected(Exception e) {
        log.error("unhandled exception", e);
        return ApiResponse.fail(500, "服务内部错误");
    }
}
