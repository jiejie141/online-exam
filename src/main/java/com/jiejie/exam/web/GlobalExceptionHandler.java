package com.jiejie.exam.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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

    // ------------------------------------------------------------------
    // 下面这几类都是**客户端**的错误，却都会掉进最后的 Exception 兜底变成
    // 500「服务内部错误」，并在日志里打一整屏堆栈。后果有两层：
    //   1. 调用方把"我发错了"读成"服务挂了"，于是去重试一个永远不会成功的请求；
    //   2. 真正的 500 被这类噪声淹没，线上排查时看不出哪条才是服务端的问题。
    // 逐个显式处理，让 4xx 归 4xx。
    // ------------------------------------------------------------------

    /** 请求体不是合法 JSON（少个引号、发了个裸字符串）。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleUnreadable(HttpMessageNotReadableException e) {
        log.warn("请求体无法解析：{}", e.getMessage());
        return ApiResponse.fail(400, "请求体不是合法的 JSON");
    }

    /** 路径不存在。以前它会走 anyRequest().authenticated() 变成 403 空响应。 */
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> handleNotFound(NoResourceFoundException e) {
        return ApiResponse.fail(404, "接口不存在：" + e.getResourcePath());
    }

    /** 方法用错（对只支持 POST 的接口发 GET）。 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public ApiResponse<Void> handleMethod(HttpRequestMethodNotSupportedException e) {
        return ApiResponse.fail(405, "该接口不支持 " + e.getMethod() + " 方法");
    }

    /** 路径变量/查询参数类型不对（{examId} 传了 "abc"）或必填参数缺失。 */
    @ExceptionHandler({MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleBadParam(Exception e) {
        return ApiResponse.fail(400, "请求参数不合法");
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleUnexpected(Exception e) {
        log.error("unhandled exception", e);
        return ApiResponse.fail(500, "服务内部错误");
    }
}
