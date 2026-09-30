package com.jiejie.exam.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * 权限边界：
     * <pre>
     *   /api/auth/**      登录、注册        —— 匿名
     *   /actuator/health  健康检查          —— 匿名
     *   /ws/**            WebSocket 握手    —— 握手时单独校验 token
     *   /api/teacher/**   出题、发布、看统计 —— ROLE_teacher
     *   其余              学生答题          —— 登录即可
     * </pre>
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtAuthenticationFilter jwtFilter) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // /ws/** 不在这里鉴权：浏览器 WebSocket 握手带不了自定义头，
                        // token 走查询参数，由 TokenHandshakeInterceptor 单独校验
                        .requestMatchers("/api/auth/**", "/actuator/health", "/actuator/info", "/ws/**").permitAll()
                        .requestMatchers("/api/teacher/**").hasRole("teacher")
                        .anyRequest().authenticated())
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
