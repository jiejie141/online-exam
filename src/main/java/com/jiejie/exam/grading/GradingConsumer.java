package com.jiejie.exam.grading;

import com.jiejie.exam.web.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class GradingConsumer {

    private static final Logger log = LoggerFactory.getLogger(GradingConsumer.class);

    private final GradingService gradingService;
    private final GradingMetrics metrics;

    public GradingConsumer(GradingService gradingService, GradingMetrics metrics) {
        this.gradingService = gradingService;
        this.metrics = metrics;
    }

    /**
     * 消费判分消息。
     *
     * <p>这里<b>不把异常抛回 Broker</b>。默认的 AUTO 确认模式下抛异常会让消息重新入队，
     * 如果这条消息本身有问题（比如答卷记录被删了），就会无限重投，把消费者打满。
     * 所以在这里把异常消化掉，不让它回到 Broker。
     *
     * <p><b>但"消化掉"不等于"一律当没事"。</b> 三种结局必须分开记：
     *
     * <ul>
     *   <li>{@link AlreadyGradedException} —— 并发下的正常重复，{@code debug} 打发了。
     *       它不是问题，记成 warn 只会把真问题淹掉。</li>
     *   <li>{@code BusinessException}（"答卷不存在"、"尚未交卷"）—— <b>真异常</b>。
     *       正常情况下不该出现，出现意味着这份卷子判不了分，必须 {@code error} 级
     *       并计入 {@code gradingFailed} 指标。</li>
     *   <li>其它 {@code Exception} —— 同上，真异常。</li>
     * </ul>
     *
     * <p>"有没有卷子没判上"不靠翻日志回答 —— 日志不会告诉你此刻积压了几份。
     * 那个问题由 {@code GradingSweepJob} 定期查 {@code score IS NULL} 的计数回答，
     * 并暴露成 {@code exam_grading_unscored} 指标。
     *
     * <p>仍未做的是<b>自动重试</b>：失败只记录，要人工重投。做对需要死信队列 + 重试计数，
     * 那是下一步。但现在至少<b>失败是可发现的了</b>，不是静默消失。
     */
    @RabbitListener(queues = GradingMq.QUEUE)
    public void onGradingMessage(Long recordId) {
        try {
            int score = gradingService.grade(recordId);
            log.info("graded recordId={} score={}", recordId, score);
        } catch (AlreadyGradedException e) {
            // 正常：MQ 至少一次投递 + 定时任务兜底都可能投第二次，设计内行为
            log.debug("grading skipped (already graded), recordId={}", recordId);
        } catch (BusinessException e) {
            // 真异常：答卷不存在、尚未交卷 —— 这份卷子这次判不了
            log.error("grading rejected, recordId={}, reason={}", recordId, e.getMessage());
            metrics.gradingFailed();
        } catch (Exception e) {
            log.error("grading failed, recordId={}", recordId, e);
            metrics.gradingFailed();
        }
    }
}
