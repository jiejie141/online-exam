package com.jiejie.exam.job;

import com.jiejie.exam.domain.entity.ExamRecord;
import com.jiejie.exam.domain.mapper.ExamRecordMapper;
import com.jiejie.exam.grading.GradingProducer;
import com.jiejie.exam.grading.GradingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 考试时间到了自动收卷。
 *
 * <p>为什么必须有这个任务：学生直接关掉浏览器就走了，不会点"交卷"。
 * 没有这个任务，那些答卷会永远停在 ongoing，老师看到的"已交卷人数"永远少一截，
 * 学生的卷子也永远不会判。
 *
 * <p>收卷仍然是走 {@code markSubmitted} 那条带 {@code status='ongoing'} 条件的 UPDATE：
 * 学生正好在这一刻自己点了交卷，那么两个操作只有一个能生效，不会重复判分。
 *
 * <p>多实例部署时外面还套了一层 {@link JobLock}：不是为了让上面的幂等"更安全"，
 * 而是为了避免两个实例捞到同一批行、把同一份卷子往 MQ 里发两遍。
 * Redis 不可用时 {@code runIfLocked} 会退回无锁执行 —— 幂等性兜得住，
 * 不能因为锁不可用就不收卷。
 */
@Component
public class AutoSubmitJob {

    private static final Logger log = LoggerFactory.getLogger(AutoSubmitJob.class);

    private final ExamRecordMapper recordMapper;
    private final GradingProducer gradingProducer;
    private final GradingService gradingService;
    private final Clock clock;
    private final int batchSize;
    private final JobLock jobLock;

    public AutoSubmitJob(ExamRecordMapper recordMapper,
                         GradingProducer gradingProducer,
                         GradingService gradingService,
                         Clock clock,
                         @Value("${exam.auto-submit.batch-size}") int batchSize,
                         JobLock jobLock) {
        this.recordMapper = recordMapper;
        this.gradingProducer = gradingProducer;
        this.gradingService = gradingService;
        this.clock = clock;
        this.batchSize = batchSize;
        this.jobLock = jobLock;
    }

    /** fixedDelayString 里的纯数字按毫秒解析 */
    @Scheduled(fixedDelayString = "${exam.auto-submit.fixed-delay-ms}")
    public void run() {
        // 锁 TTL 给 5 分钟：这一轮最多处理 batchSize(200) 份，
        // 每份一次 UPDATE + 一次 MQ 发送，正常是秒级。留足余量，
        // 免得锁在任务跑完前过期、另一个实例同时进来。
        jobLock.runIfLocked("auto-submit", 300, () -> {
            sweep();
            return null;
        });
    }

    private void sweep() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<ExamRecord> expired = recordMapper.findExpiredOngoing(now, batchSize);
        if (expired.isEmpty()) {
            return;
        }
        for (ExamRecord record : expired) {
            // 用时按"开始 → 应截止"记，而不是"开始 → 现在"。
            // 现在可能比 deadline 晚很多（比如服务停了十分钟才恢复），
            // 那十分钟不是学生的答题时间。
            int seconds = (int) Math.max(0, Duration.between(record.getStartedAt(), record.getDeadlineAt()).toSeconds());
            int rows = recordMapper.markSubmitted(record.getId(), now, seconds);
            if (rows == 0) {
                continue;
            }
            try {
                if (!gradingProducer.trySend(record.getId())) {
                    // Broker 不可用：这一份就地同步判掉，不能挂在"已交卷未判分"
                    gradingService.grade(record.getId());
                }
                log.info("auto submitted recordId={} seconds={}", record.getId(), seconds);
            } catch (Exception e) {
                log.error("auto submit grading failed, recordId={}", record.getId(), e);
            }
        }
    }
}
