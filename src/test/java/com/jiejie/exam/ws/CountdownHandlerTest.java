package com.jiejie.exam.ws;

import com.jiejie.exam.domain.entity.ExamRecord;
import com.jiejie.exam.domain.mapper.ExamRecordMapper;
import com.jiejie.exam.security.Principal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 倒计时推送。裁决权在服务端 —— 剩余时间来自库里的 deadline_at，
 * 不是客户端传上来的任何值。测试钉住三件事：
 *
 * <ol>
 *   <li>没进过这场考试不让连：否则拼一个 examId 就能连上别人的倒计时。</li>
 *   <li>到点推 0 而不是负数：客户端收到负数会显示成乱码，或被当成没结束。</li>
 *   <li>推送失败要摘掉连接：否则它每 5 秒失败一次、永远留在 map 里，
 *       onlineCount 也不再可信。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class CountdownHandlerTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 12, 10, 0);
    private static final Clock FIXED =
            Clock.fixed(NOW.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());

    @Mock
    private ExamRecordMapper recordMapper;

    private CountdownHandler handler;

    @BeforeEach
    void setUp() {
        handler = new CountdownHandler(recordMapper, FIXED);
    }

    private WebSocketSession session(Principal principal, Long examId) {
        WebSocketSession s = mock(WebSocketSession.class);
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("principal", principal);
        attrs.put("examId", examId);
        when(s.getAttributes()).thenReturn(attrs);
        return s;
    }

    private ExamRecord record(LocalDateTime deadline) {
        ExamRecord r = new ExamRecord();
        r.setId(500L);
        r.setExamId(1L);
        r.setUserId(7L);
        r.setDeadlineAt(deadline);
        return r;
    }

    private Principal student() {
        return new Principal(7L, "s001", "student", "张三");
    }

    private String sentPayload(WebSocketSession s) {
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        try {
            verify(s).sendMessage(captor.capture());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return captor.getValue().getPayload();
    }

    private String expected(long remain, boolean ended) {
        return "{\"remainingSeconds\":" + remain + ",\"ended\":" + ended + "}";
    }

    // ---------------- 握手后的准入 ----------------

    @Test
    void 没进过这场考试的不让连并断开() throws Exception {
        // 没有这条检查，任何人拼一个 examId 就能连上别人的倒计时
        WebSocketSession s = session(student(), 1L);
        when(recordMapper.findByExamAndUser(1L, 7L)).thenReturn(null);

        handler.afterConnectionEstablished(s);

        verify(s).close(any(CloseStatus.class));
        assertEquals(0, handler.onlineCount(), "没准入的连接不该计入在线数");
        verify(s, never()).sendMessage(any());
    }

    // ---------------- 剩余时间算法 ----------------

    @Test
    void 连上后立刻推一次而不是等下一个tick() throws Exception {
        WebSocketSession s = session(student(), 1L);
        when(recordMapper.findByExamAndUser(1L, 7L)).thenReturn(record(NOW.plusMinutes(30)));

        handler.afterConnectionEstablished(s);

        assertEquals(1, handler.onlineCount());
        assertEquals(expected(1800, false), sentPayload(s));
    }

    @Test
    void 还没到点推剩余秒数且ended为false() throws Exception {
        WebSocketSession s = session(student(), 1L);
        when(recordMapper.findByExamAndUser(1L, 7L)).thenReturn(record(NOW.plusSeconds(90)));

        handler.afterConnectionEstablished(s);

        assertEquals(expected(90, false), sentPayload(s));
    }

    @Test
    void 已过截止时间推0而不是负数() throws Exception {
        // 过了 deadline 还算，remain 是负数。直接推出去客户端会显示
        // "还剩 -300 秒"，或者把负数当成"还没结束"
        WebSocketSession s = session(student(), 1L);
        when(recordMapper.findByExamAndUser(1L, 7L)).thenReturn(record(NOW.minusMinutes(5)));

        handler.afterConnectionEstablished(s);

        assertEquals(expected(0, true), sentPayload(s));
    }

    @Test
    void 刚好到点的那一秒算结束() throws Exception {
        // 边界：remain == 0 必须走 ended 分支（判断是 remain > 0）
        WebSocketSession s = session(student(), 1L);
        when(recordMapper.findByExamAndUser(1L, 7L)).thenReturn(record(NOW));

        handler.afterConnectionEstablished(s);

        assertEquals(expected(0, true), sentPayload(s));
    }

    @Test
    void 只剩一秒时还不算结束() throws Exception {
        // 另一侧边界：remain == 1 仍要走倒计时分支
        WebSocketSession s = session(student(), 1L);
        when(recordMapper.findByExamAndUser(1L, 7L)).thenReturn(record(NOW.plusSeconds(1)));

        handler.afterConnectionEstablished(s);

        assertEquals(expected(1, false), sentPayload(s));
    }

    // ---------------- 广播 ----------------

    @Test
    void 没有连接时广播直接返回() {
        handler.pushAll();

        assertEquals(0, handler.onlineCount());
    }

    @Test
    void 广播推给所有连接() throws Exception {
        WebSocketSession a = session(student(), 1L);
        WebSocketSession b = session(new Principal(8L, "s002", "student", "李四"), 1L);
        when(recordMapper.findByExamAndUser(1L, 7L)).thenReturn(record(NOW.plusMinutes(10)));
        when(recordMapper.findByExamAndUser(1L, 8L)).thenReturn(record(NOW.plusMinutes(10)));
        handler.afterConnectionEstablished(a);
        handler.afterConnectionEstablished(b);

        handler.pushAll();

        // 握手各推 1 次 + 广播各推 1 次
        verify(a, times(2)).sendMessage(any());
        verify(b, times(2)).sendMessage(any());
    }

    @Test
    void 推送失败的连接被摘掉且不影响其他连接() throws Exception {
        // 断掉的连接留在 map 里的话，每 5 秒失败一次、永远不会自己消失，
        // onlineCount（actuator 会读）也就不再可信
        WebSocketSession dead = session(student(), 1L);
        WebSocketSession alive = session(new Principal(8L, "s002", "student", "李四"), 1L);
        when(recordMapper.findByExamAndUser(1L, 7L)).thenReturn(record(NOW.plusMinutes(10)));
        when(recordMapper.findByExamAndUser(1L, 8L)).thenReturn(record(NOW.plusMinutes(10)));
        handler.afterConnectionEstablished(dead);
        handler.afterConnectionEstablished(alive);
        assertEquals(2, handler.onlineCount());

        doThrow(new IOException("connection reset")).when(dead).sendMessage(any());
        handler.pushAll();

        assertEquals(1, handler.onlineCount(), "失败的连接要摘掉，存活的要留下");
    }

    // ---------------- 关闭 ----------------

    @Test
    void 连接关闭后从在线数去掉() throws Exception {
        WebSocketSession s = session(student(), 1L);
        when(recordMapper.findByExamAndUser(1L, 7L)).thenReturn(record(NOW.plusMinutes(10)));
        handler.afterConnectionEstablished(s);
        assertEquals(1, handler.onlineCount());

        handler.afterConnectionClosed(s, CloseStatus.NORMAL);

        assertEquals(0, handler.onlineCount());
    }

    @Test
    void 关一个没登记过的连接不会出错() {
        // 调用方可能重复调 afterConnectionClosed（比如握手失败的连接）
        WebSocketSession s = mock(WebSocketSession.class);

        handler.afterConnectionClosed(s, CloseStatus.NORMAL);

        assertEquals(0, handler.onlineCount());
    }

    @Test
    void 广播重复推的内容一致() throws Exception {
        // 登记时把 deadline 存进 SessionInfo，后续广播用这份快照。
        // 时钟固定 + deadline 不变 => 推两次应该完全一样。
        // 钉住这一点，免得以后有人以为广播会重新查库。
        WebSocketSession s = session(student(), 1L);
        when(recordMapper.findByExamAndUser(1L, 7L)).thenReturn(record(NOW.plusMinutes(10)));
        handler.afterConnectionEstablished(s);

        handler.pushAll();

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(s, times(2)).sendMessage(captor.capture());
        assertEquals(captor.getAllValues().get(0).getPayload(),
                captor.getAllValues().get(1).getPayload());
        // 而且每次广播只查一次库（握手那次），说明没恢复成每 tick 查库
        verify(recordMapper, times(1)).findByExamAndUser(1L, 7L);
    }
}