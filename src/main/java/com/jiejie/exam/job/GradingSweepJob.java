package com.jiejie.exam.job;

import com.jiejie.exam.domain.mapper.ExamRecordMapper;
import com.jiejie.exam.grading.GradingMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定期数一遍"交过卷但没判分"的记录，把数字放进指标。
 *
 * <p><b>为什么需要这样一个任务</b>：判分走的 MQ 消费者有意不把异常抛回 Broker
 * （抛回去会无限重投），代价是失败的卷子<b>静默</b>留在 {@code status='submitted',
 * score IS NULL} 这个状态里。在此之前，想发现它们只能人肉去翻日志 ——
 * 而日志里"正常重复判分"和"真的判不了分"曾经长得一样，翻了也未必看得出来。
 *
 * <p>这个任务只做一件事：<b>把那个数字做成可查的指标</b>。
 * 它不重投、不重判 —— 重试是另一件事，需要死信队列和重试计数，
 * 混在这里做会让"发现"和"修复"两个职责缠在一起，出问题时分不清是哪个环节的锅。
 *
 * <p>换句话说：<b>这个任务负责让问题可见，不负责解决问题。</b>
 * 可见之后怎么做（人工重投 / 加告警 / 补死信队列）是下一步的选择。
 */
@Component
public class GradingSweepJob {

    private static final Logger log = LoggerFactory.getLogger(GradingSweepJob.class);

    private final ExamRecordMapper recordMapper;
    private final GradingMetrics metrics;

    public GradingSweepJob(ExamRecordMapper recordMapper, GradingMetrics metrics) {
        this.recordMapper = recordMapper;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${exam.grading.sweep-delay-ms}")
    public void run() {
        try {
            long unscored = recordMapper.countUnscored();
            metrics.refreshUnscored(unscored);
            if (unscored > 0) {
                // 只在 > 0 时打日志：正常情况下这个任务应该完全安静，
                // 否则每 30 秒一行"0 条漏判"会把真正要看的东西淹掉。
                log.warn("found {} unscored submitted record(s), see exam_grading_unscored", unscored);
            }
        } catch (Exception e) {
            // 扫描任务本身失败不能把调度线程搞死 —— 下一轮 fixedDelay 还会再来
            log.error("grading sweep failed", e);
        }
    }
}
