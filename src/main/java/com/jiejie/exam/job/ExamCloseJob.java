package com.jiejie.exam.job;

import com.jiejie.exam.domain.entity.Exam;
import com.jiejie.exam.domain.mapper.ExamMapper;
import com.jiejie.exam.exam.ExamStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 到点关考：把"时间窗已过但仍挂着 published"的考试置为 closed。
 *
 * <p>关考和收卷是两件事，所以拆成两个任务：
 * 先由 AutoSubmitJob 把还在答题的人收掉，再由这里把考试状态关掉。
 * 顺序反了会导致学生正好卡在结束时刻进不了场，也交不了卷。
 *
 * <p>这里没有 {@code WHERE status='published'} 的条件 UPDATE，多实例同时跑会重复 UPDATE
 * 同一行（结果一样，只是多打一遍日志）。所以外面套 {@link JobLock}。
 */
@Component
public class ExamCloseJob {

    private static final Logger log = LoggerFactory.getLogger(ExamCloseJob.class);

    private final ExamMapper examMapper;
    private final Clock clock;
    private final JobLock jobLock;

    public ExamCloseJob(ExamMapper examMapper, Clock clock, JobLock jobLock) {
        this.examMapper = examMapper;
        this.clock = clock;
        this.jobLock = jobLock;
    }

    @Scheduled(fixedDelay = 60_000)
    public void run() {
        // TTL 60 秒与调度间隔相同：正常一轮是毫秒级，锁早就释放了；
        // 真卡住超过一分钟，让下一轮照常进来，不要一路堵死。
        jobLock.runIfLocked("exam-close", 60, () -> {
            sweep();
            return null;
        });
    }

    private void sweep() {
        List<Exam> ended = examMapper.findPublishedEndedBefore(LocalDateTime.now(clock));
        if (ended.isEmpty()) {
            return;
        }
        int closed = 0;
        for (Exam exam : ended) {
            // 逐场兜住异常：一场更新失败不该让这一轮剩下的考试全被跳过。
            // 下一轮（60 秒后）会重新捞到它，所以不用在这里重试。
            try {
                exam.setStatus(ExamStatus.CLOSED.code());
                examMapper.updateById(exam);
                closed++;
                log.info("exam closed, examId={} title={}", exam.getId(), exam.getTitle());
            } catch (Exception e) {
                log.error("close exam failed, examId={}", exam.getId(), e);
            }
        }
        log.debug("exam close sweep done, closed {} of {}", closed, ended.size());
    }
}
