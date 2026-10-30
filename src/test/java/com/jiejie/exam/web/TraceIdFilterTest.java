package com.jiejie.exam.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * traceId 过滤器：一次请求一个 id，日志能按 id 串起来。
 *
 * <p>这个测试盯的是三个容易在重构中悄悄丢掉的点：
 * <ol>
 *   <li>响应头要回写 traceId —— 没有它，用户报障时说不出一个可查的 id；</li>
 *   <li>请求结束后 MDC 必须清干净 —— Tomcat 复用线程，不清会把 id 串到下一个请求上；</li>
 *   <li>外部传入的 id 必须过滤 —— 允许换行就能伪造日志行（log injection）。</li>
 * </ol>
 */
class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();

    @AfterEach
    void clearMdc() {
        // 测试之间互不影响：万一某个断言失败导致 finally 没跑到，这里兜住
        MDC.clear();
    }

    @Test
    void 没有传入id时自动生成并写回响应头() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/exams/available");
        MockHttpServletResponse response = new MockHttpServletResponse();
        StringBuilder seenInChain = new StringBuilder();
        FilterChain chain = (req, res) -> seenInChain.append(MDC.get(TraceIdFilter.MDC_KEY));

        filter.doFilter(request, response, chain);

        String traceId = response.getHeader(TraceIdFilter.HEADER);
        assertNotNull(traceId, "响应头里必须带 traceId");
        assertEquals(16, traceId.length(), "生成的 id 是 UUID 去横线后的前 16 位");
        assertEquals(traceId, seenInChain.toString(), "过滤器链里要能读到同一个 id");
    }

    @Test
    void 请求结束后必须清掉MDC() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/exams/available");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> { };

        filter.doFilter(request, response, chain);

        // 这条是重点：Tomcat 线程是复用的，不清的话下一个请求会继承上一个的 traceId，
        // 两条不相干的请求在日志里变成同一条 —— 比没有 traceId 更糟。
        assertNull(MDC.get(TraceIdFilter.MDC_KEY));
    }

    @Test
    void 请求抛异常时也要清掉MDC() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/exams/available");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> {
            throw new IllegalStateException("下游炸了");
        };

        try {
            filter.doFilter(request, response, chain);
        } catch (IllegalStateException expected) {
            // 过滤器不负责吞异常，只负责清理
        }

        assertNull(MDC.get(TraceIdFilter.MDC_KEY));
    }

    @Test
    void 合法的传入id原样沿用() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/exams/available");
        request.addHeader(TraceIdFilter.HEADER, "abc-123_XYZ.9");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> { };

        filter.doFilter(request, response, chain);

        assertEquals("abc-123_XYZ.9", response.getHeader(TraceIdFilter.HEADER));
    }

    @Test
    void 带换行的传入id被丢弃并重新生成() throws Exception {
        // log injection：允许 \n 就能凭空造出一行假日志
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/exams/available");
        request.addHeader(TraceIdFilter.HEADER, "ok\n2026-10-30 12:00:00 ERROR 有人登录成功了");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> { };

        filter.doFilter(request, response, chain);

        String traceId = response.getHeader(TraceIdFilter.HEADER);
        assertFalse(traceId.contains("\n"));
        assertFalse(traceId.contains("ERROR"));
        assertEquals(16, traceId.length());
    }

    @Test
    void 超长的传入id被丢弃并重新生成() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/exams/available");
        request.addHeader(TraceIdFilter.HEADER, "a".repeat(65));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> { };

        filter.doFilter(request, response, chain);

        assertEquals(16, response.getHeader(TraceIdFilter.HEADER).length());
    }

    @Test
    void 空白的传入id被当成没有() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/exams/available");
        request.addHeader(TraceIdFilter.HEADER, "   ");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> { };

        filter.doFilter(request, response, chain);

        assertEquals(16, response.getHeader(TraceIdFilter.HEADER).length());
    }

    @Test
    void 每次请求的id都不一样() throws Exception {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < 20; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(new MockHttpServletRequest("GET", "/x"), response, (req, res) -> { });
            assertTrue(seen.add(response.getHeader(TraceIdFilter.HEADER)), "traceId 必须唯一");
        }
    }
}
