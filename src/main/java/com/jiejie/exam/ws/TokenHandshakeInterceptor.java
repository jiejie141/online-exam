package com.jiejie.exam.ws;

import com.jiejie.exam.security.JwtUtil;
import com.jiejie.exam.security.Principal;
import io.jsonwebtoken.JwtException;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * WebSocket 握手时校验 token。
 *
 * <p>浏览器原生 WebSocket 不能在握手时带上自定义请求头，所以 token 只能走查询参数。
 * 这也意味着 Spring Security 那条基于 Authorization 头的过滤器在这里用不上，
 * 必须在握手阶段单独校验一次 —— 少了这一步，任何人拼个 URL 就能连上推送通道。
 */
public class TokenHandshakeInterceptor implements HandshakeInterceptor {

    private final JwtUtil jwtUtil;

    public TokenHandshakeInterceptor(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (!(request instanceof ServletServerHttpRequest servletRequest)) {
            response.setStatusCode(HttpStatus.BAD_REQUEST);
            return false;
        }
        String token = servletRequest.getServletRequest().getParameter("token");
        String examId = servletRequest.getServletRequest().getParameter("examId");
        if (token == null || examId == null) {
            response.setStatusCode(HttpStatus.BAD_REQUEST);
            return false;
        }
        try {
            Principal principal = jwtUtil.parse(token);
            attributes.put("principal", principal);
            attributes.put("examId", Long.valueOf(examId));
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // 无状态可清理
    }
}
