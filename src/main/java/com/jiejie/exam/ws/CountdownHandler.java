package com.jiejie.exam.ws;

import com.jiejie.exam.domain.entity.ExamRecord;
import com.jiejie.exam.domain.mapper.ExamRecordMapper;
import com.jiejie.exam.security.Principal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 考试倒计时推送。连上之后服务端每 5 秒推一次剩余秒数，到点推一次 ended。
 *
 * <p><b>剩余时间由服务端算，而且用的是数据库里的 deadline_at，不是客户端传来的任何值。</b>
 * 学生改一下浏览器本地时间就想多考半小时 —— 这条路在这里是堵死的：
 * 客户端时间只影响"显示"，判有没有超时的唯一依据是服务端的 deadline_at。
 *
 * <p>为什么不用前端自己 setInterval 倒计时：页面刷新、切后台休眠都会让本地计时漂移，
 * 而且前端计时等于把"还剩多久"这件事的裁决权交给了客户端。
 */
@Component
public class CountdownHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(CountdownHandler.class);

    private record SessionInfo(Long examId, Long userId, LocalDateTime deadline) {
    }

    private final Map<WebSocketSession, SessionInfo> sessions = new ConcurrentHashMap<>();
    private final ExamRecordMapper recordMapper;
    private final Clock clock;

    public CountdownHandler(ExamRecordMapper recordMapper, Clock clock) {
        this.recordMapper = recordMapper;
        this.clock = clock;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        Principal principal = (Principal) session.getAttributes().get("principal");
        Long examId = (Long) session.getAttributes().get("examId");
        ExamRecord record = recordMapper.findByExamAndUser(examId, principal.userId());
        if (record == null) {
            // 还没进入这场考试，不该连倒计时
            session.close(CloseStatus.POLICY_VIOLATION.withReason("no exam record"));
            return;
        }
        sessions.put(session, new SessionInfo(examId, principal.userId(), record.getDeadlineAt()));
        push(session, record.getDeadlineAt());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
    }

    @Scheduled(fixedDelay = 5_000)
    public void pushAll() {
        if (sessions.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        for (Map.Entry<WebSocketSession, SessionInfo> e : sessions.entrySet()) {
            try {
                push(e.getKey(), e.getValue().deadline());
            } catch (Exception ex) {
                log.warn("push countdown failed", ex);
                sessions.remove(e.getKey());
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("countdown pushed to {} sessions at {}", sessions.size(), now);
        }
    }

    /** 当前连接数，暴露给 actuator 观察用 */
    public int onlineCount() {
        return sessions.size();
    }

    private void push(WebSocketSession session, LocalDateTime deadline) throws IOException {
        long remain = Duration.between(LocalDateTime.now(clock), deadline).toSeconds();
        String payload = remain > 0
                ? "{\"remainingSeconds\":" + remain + ",\"ended\":false}"
                : "{\"remainingSeconds\":0,\"ended\":true}";
        synchronized (session) {
            session.sendMessage(new TextMessage(payload));
        }
    }
}
