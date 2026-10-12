package com.jiejie.exam.grading;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 判分这块的对外可观测指标。
 *
 * <p>为什么需要它：判分失败在消费者里被吞掉（不吞会被 Broker 无限重投），
 * 于是"这份卷子没判上"这件事在系统里**没有任何痕迹**，除了日志里一行 error。
 * 而日志回答不了"此刻积压了几份" —— 那是操作上的问题，需要的是**数字**，不是文本。
 *
 * <p>暴露在 Actuator 上（{@code /actuator/prometheus}），Prometheus 直接能抓：
 *
 * <ul>
 *   <li>{@code exam_grading_failed_total} —— 判分失败累计次数。只增不减；
 *       重启归零，所以配 {@code rate()} 用。</li>
 *   <li>{@code exam_grading_unscored} —— <b>此刻</b>已交卷但没分的记录数。
 *       由 {@link GradingSweepJob} 定期刷新。这是真正要盯的那个数：
 *       它持续不为 0 就说明有卷子卡住了。</li>
 * </ul>
 *
 * <p>用 Micrometer 而不是自己写个静态计数器：依赖已经在了
 * （{@code micrometer-registry-prometheus}，见 pom），且能自动带上
 * Actuator 的标签体系，不重复造。
 */
@Component
public class GradingMetrics {

    private final Counter failed;
    private final AtomicLong unscored = new AtomicLong(0);

    public GradingMetrics(MeterRegistry registry) {
        this.failed = Counter.builder("exam.grading.failed")
                .description("判分失败累计次数（不含并发下的正常重复判分）")
                .register(registry);
        Gauge.builder("exam.grading.unscored", unscored, AtomicLong::get)
                .description("此刻已交卷但分数为 NULL 的记录数 —— 持续大于 0 说明有卷子卡住了")
                .register(registry);
    }

    /** 判分失败一次。并发下的正常重复<b>不该</b>调这个，否则指标会被噪声填满。 */
    public void gradingFailed() {
        failed.increment();
    }

    /** 刷新"当前漏判数"。由扫描任务调用。 */
    public void refreshUnscored(long count) {
        unscored.set(count);
    }

    /** 供测试断言用，避免测试为了读一个数去把整个 MeterRegistry 翻一遍。 */
    public long unscoredCount() {
        return unscored.get();
    }

    public double failedCount() {
        return failed.count();
    }
}
