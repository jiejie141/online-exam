package com.jiejie.exam.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 给每个 HTTP 请求打一个 traceId，放进 MDC，日志格式里带出来。
 *
 * <p><b>为什么需要它</b>：一次交卷会在日志里留下好几行（请求、改状态、投 MQ、
 * 判分）。并发一上来，这些行会和其他请求的行交错在一起，
 * 靠时间戳和线程名根本分不清哪几行是同一个学生的。有了 traceId，
 * 一次「交卷后没出分」的排查就是一次 grep，而不是一次考古。
 *
 * <p>为什么还要把 traceId 写回响应头：线上出问题时，用户/前端能报出一个
 * 具体的 id，服务端就能直接定位那一次请求。没有它，只能按时间范围翻日志。
 *
 * <p>为什么是 HIGHEST_PRECEDENCE：它要在所有会打日志的过滤器之前生效，
 * 包括 Spring Security 的那条链 —— 否则「鉴权失败」这类最早的日志反而没有 traceId，
 * 而那恰恰是最需要 traceId 的一类。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    /** MDC 里的键名，与 logback-spring.xml 里的 %X{traceId} 必须一致 */
    public static final String MDC_KEY = "traceId";

    /** 沿用业界通用的请求 id 头名，方便放在网关/nginx 后面时串起来 */
    public static final String HEADER = "X-Request-Id";

    private static final int MAX_INCOMING_LENGTH = 64;
    private static final int GENERATED_LENGTH = 16;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String traceId = normalize(request.getHeader(HEADER));
        MDC.put(MDC_KEY, traceId);
        response.setHeader(HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            // 必须清掉：Tomcat 复用线程，不清理的话下一个请求会继承上一个的 traceId，
            // 于是两条不相干的请求在日志里变成同一条 —— 比没有 traceId 更糟。
            MDC.remove(MDC_KEY);
        }
    }

    /**
     * 外部传进来的 id 不能直接写进日志。
     *
     * <p>它可以带换行或控制字符：一个带 {@code \n} 的头会让一行日志劈成两行，
     * 等于让人凭空伪造一条日志（log injection）。所以只接受
     * 字母/数字/-/_/. 且不超过 64 字符，其它一律重新生成。
     *
     * <p>另外：长度上限也防住「有人拿 1MB 的头把日志撑爆」。
     */
    static String normalize(String incoming) {
        if (incoming == null || incoming.isBlank()) {
            return newTraceId();
        }
        String trimmed = incoming.trim();
        if (trimmed.length() > MAX_INCOMING_LENGTH) {
            return newTraceId();
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.';
            if (!allowed) {
                return newTraceId();
            }
        }
        return trimmed;
    }

    private static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, GENERATED_LENGTH);
    }
}
