package com.jiejie.exam.ws;

import com.jiejie.exam.security.JwtUtil;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final CountdownHandler countdownHandler;
    private final JwtUtil jwtUtil;

    public WebSocketConfig(CountdownHandler countdownHandler, JwtUtil jwtUtil) {
        this.countdownHandler = countdownHandler;
        this.jwtUtil = jwtUtil;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(countdownHandler, "/ws/countdown")
                .addInterceptors(new TokenHandshakeInterceptor(jwtUtil))
                .setAllowedOriginPatterns("*");
    }
}
