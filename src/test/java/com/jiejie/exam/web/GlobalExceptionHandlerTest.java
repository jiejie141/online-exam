package com.jiejie.exam.web;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 异常到 HTTP 响应的翻译层。
 *
 * <p>这一层没有业务逻辑，但它决定了「调用方看到的到底是什么」，
 * 而它历史上真的错过两次（下面每一条都对应一次）：
 *
 * <ol>
 *   <li>业务异常的状态码写死了 400，于是登录失败时 HTTP 是 400、body 里的
 *       code 却是 401 —— 两边说不同的话。只判断 HTTP 状态的调用方
 *       会把「口令错」当成「参数错」处理。</li>
 *   <li>几类明显的客户端错误（JSON 坏了、方法用错、参数类型不对）没有显式处理，
 *       全都掉进 Exception 兜底变成 500，并在日志里打一整屏堆栈。
 *       后果是「我发错了」被读成「服务挂了」，调用方去重试一个永远不会成功的请求；
 *       同时真正的 500 被这类噪声淹没。</li>
 * </ol>
 *
 * <p>这两点都是「行为契约」而不是「实现细节」，所以值得钉住：
 * 谁来改这个文件，都不该在不知情的情况下把它们改回去。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    // ---------- 业务异常：状态码必须跟着 code 走 ----------

    @Test
    void 业务异常的HTTP状态码跟着code走_401() {
        ResponseEntity<ApiResponse<Void>> resp =
                handler.handleBusiness(new BusinessException(401, "用户名或密码不对"));

        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals(401, resp.getBody().code());
        assertEquals("用户名或密码不对", resp.getBody().message());
    }

    @Test
    void 业务异常的HTTP状态码跟着code走_403() {
        ResponseEntity<ApiResponse<Void>> resp =
                handler.handleBusiness(new BusinessException(403, "只有老师可以建账号"));

        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
        assertEquals(403, resp.getBody().code());
    }

    @Test
    void 业务异常的HTTP状态码跟着code走_404() {
        ResponseEntity<ApiResponse<Void>> resp =
                handler.handleBusiness(new BusinessException(404, "找不到"));

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
        assertEquals(404, resp.getBody().code());
    }

    @Test
    void 未列出的code一律降级成400_不会误报成500() {
        ResponseEntity<ApiResponse<Void>> resp =
                handler.handleBusiness(new BusinessException(422, "考试还没结束"));

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals(422, resp.getBody().code());
    }

    @Test
    void 单参数构造的BusinessException默认是400() {
        BusinessException e = new BusinessException("参数不对");

        assertEquals(400, e.code());
        ResponseEntity<ApiResponse<Void>> resp = handler.handleBusiness(e);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    void 业务异常的响应体里不带data() {
        ResponseEntity<ApiResponse<Void>> resp =
                handler.handleBusiness(new BusinessException(400, "错了"));

        assertNull(resp.getBody().data());
    }

    // ---------- 客户端错误不能变成 500 ----------

    @Test
    void 请求体不是合法JSON返回400而不是500() {
        // 这是第 2 个 bug 的第一种形态：以前它会掉进兜底变成 500。
        // 调用方收到 500 会认为「服务挂了」，然后去重试一个永远不会成功的请求。
        ApiResponse<Void> resp = handler.handleUnreadable(
                new HttpMessageNotReadableException("JSON parse error"));

        assertEquals(400, resp.code());
    }

    @Test
    void 方法用错返回405而不是500() {
        ApiResponse<Void> resp = handler.handleMethod(
                new HttpRequestMethodNotSupportedException("GET"));

        assertEquals(405, resp.code());
        // 消息里要带上实际用的方法，否则调用方不知道该怎么改
        assertEquals("该接口不支持 GET 方法", resp.message());
    }

    @Test
    void 路径变量类型不对返回400而不是500() {
        // {examId} 传了 "abc"：这是调用方的问题，不是服务端的问题
        ApiResponse<Void> resp = handler.handleBadParam(
                new MethodArgumentTypeMismatchException("abc", Long.class, "examId", null, null));

        assertEquals(400, resp.code());
    }

    @Test
    void 必填查询参数缺失也返回400() {
        ApiResponse<Void> resp = handler.handleBadParam(
                new MissingServletRequestParameterException("examId", "Long"));

        assertEquals(400, resp.code());
    }

    // ---------- 兜底：真的服务端错误才给 500 ----------

    @Test
    void 未预期的异常才返回500_且不泄露堆栈给前端() {
        ApiResponse<Void> resp = handler.handleUnexpected(
                new IllegalStateException("connection pool exhausted"));

        assertEquals(500, resp.code());
        // 消息必须是固定文案：把原始异常信息吐回去会泄露实现细节
        assertEquals("服务内部错误", resp.message());
        assertNull(resp.data());
    }

    // ---------- ApiResponse 的两个工厂 ----------

    @Test
    void ok的code是0且带data() {
        ApiResponse<String> resp = ApiResponse.ok("hello");

        assertEquals(0, resp.code());
        assertEquals("ok", resp.message());
        assertEquals("hello", resp.data());
    }

    @Test
    void fail没有data() {
        ApiResponse<String> resp = ApiResponse.fail(403, "不许");

        assertEquals(403, resp.code());
        assertNull(resp.data());
    }

    @Test
    void ok可以包null_表达成功但无内容() {
        // saveAnswer 就是这么用的：成功，但没有返回值
        ApiResponse<Void> resp = ApiResponse.ok(null);

        assertEquals(0, resp.code());
        assertNull(resp.data());
    }
}
