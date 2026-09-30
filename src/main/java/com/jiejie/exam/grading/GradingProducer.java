package com.jiejie.exam.grading;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/** 交卷后丢一条"该判哪份卷子"的消息。消息体只放 recordId，判分时再查库取最新数据。 */
@Component
public class GradingProducer {

    private static final Logger log = LoggerFactory.getLogger(GradingProducer.class);

    private final RabbitTemplate rabbitTemplate;

    public GradingProducer(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * 发消息。返回 false 表示没发出去（Broker 不可用），调用方要降级成同步判分。
     *
     * <p>为什么不直接抛异常：投递失败不该让学生交卷失败，更不该让卷子永远停在"已交卷未判分"。
     * 宁可这一份慢一点同步判掉，也不能丢。
     */
    public boolean trySend(Long recordId) {
        try {
            rabbitTemplate.convertAndSend(GradingMq.EXCHANGE, GradingMq.ROUTING, recordId);
            return true;
        } catch (Exception e) {
            log.error("send grading message failed, recordId={}, fallback to sync grading", recordId, e);
            return false;
        }
    }
}
