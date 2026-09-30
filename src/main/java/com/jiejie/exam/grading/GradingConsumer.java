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

    public GradingConsumer(GradingService gradingService) {
        this.gradingService = gradingService;
    }

    /**
     * 消费判分消息。
     *
     * <p>这里<b>不把异常抛回 Broker</b>。默认的 AUTO 确认模式下抛异常会让消息重新入队，
     * 如果这条消息本身有问题（比如答卷记录被删了），就会无限重投，把消费者打满。
     * 所以一律记日志吞掉，靠 score IS NULL 的记录数去发现漏判的卷子。
     *
     * <p>代价是"真的偶发失败会漏判"。要补上这一点，正确做法是配死信队列 + 重试计数，
     * 那是这个项目的下一步，现在先诚实标注而不是假装已经解决。
     */
    @RabbitListener(queues = GradingMq.QUEUE)
    public void onGradingMessage(Long recordId) {
        try {
            int score = gradingService.grade(recordId);
            log.info("graded recordId={} score={}", recordId, score);
        } catch (BusinessException e) {
            log.warn("grading skipped, recordId={}, reason={}", recordId, e.getMessage());
        } catch (Exception e) {
            log.error("grading failed, recordId={}", recordId, e);
        }
    }
}
