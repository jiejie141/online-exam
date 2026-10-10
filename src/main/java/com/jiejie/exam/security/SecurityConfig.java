package com.jiejie.exam.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
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
                // 不配 exceptionHandling 时，Spring Security 对未认证请求用的是
                // Http403ForbiddenEntryPoint —— 于是"没带令牌"会返回 **403 空响应体**。
                // 那是错的：403 的语义是"你是谁我知道了，但你不许"，而这里是
                // "我不知道你是谁"。客户端拿不到任何 body，也没法区分
                // "没登录"和"没权限"，只能一律弹一句"无权限"。
                // 这里把两种失败分开：未认证 → 401，已认证但越权 → 403，都带 JSON body。
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(unauthorizedEntryPoint())
                        .accessDeniedHandler(forbiddenHandler()))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /** 未认证（没带令牌 / 令牌无效）→ 401 + 统一响应结构。 */
    @Bean
    public AuthenticationEntryPoint unauthorizedEntryPoint() {
        return (request, response, authException) ->
                writeJson(response, 401, "未登录或登录已失效，请重新登录");
    }

    /** 已认证但权限不足（学生打老师接口）→ 403 + 统一响应结构。 */
    @Bean
    public AccessDeniedHandler forbiddenHandler() {
        return (request, response, deniedException) ->
                writeJson(response, 403, "无权访问该资源");
    }

    /**
     * 直接写 JSON，不经过 {@code ApiResponse} 的序列化器 ——
     * 这个位置在 DispatcherServlet 之前，拿不到 MVC 的消息转换器。
     * 手工拼是因为这里只有两个固定字符串，引不进注入风险。
     */
    private static void writeJson(jakarta.servlet.http.HttpServletResponse response,
                                  int code, String message) throws java.io.IOException {
        response.setStatus(code);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"code\":" + code + ",\"message\":\"" + message + "\",\"data\":null}");
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
