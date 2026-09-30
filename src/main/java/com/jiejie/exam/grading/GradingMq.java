package com.jiejie.exam.grading;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 判分消息队列。
 *
 * <p>为什么交卷不直接同步判分：一份卷子 50 道题，判分要读卷面、读作答、
 * 再逐题 UPDATE，几百人同时交卷时这些写会把数据库连接池占满，
 * 交卷接口本身却只是"改个状态 + 记个时间"。把重活挪到消费者里，
 * 交卷接口就能在毫秒级返回。
 *
 * <p>用 direct 而不是 topic：目前只有"判分"一种消息，
 * 没有按规则路由的需求，topic 在这里只是增加理解成本。
 */
@Configuration
public class GradingMq {

    public static final String EXCHANGE = "exam.grading.exchange";
    public static final String QUEUE = "exam.grading.queue";
    public static final String ROUTING = "exam.grading";

    @Bean
    public DirectExchange gradingExchange() {
        return new DirectExchange(EXCHANGE, true, false);
    }

    /** durable=true：Broker 重启后消息不丢。判分消息丢了意味着学生交了卷却没分 */
    @Bean
    public Queue gradingQueue() {
        return new Queue(QUEUE, true, false, false);
    }

    @Bean
    public Binding gradingBinding(Queue gradingQueue, DirectExchange gradingExchange) {
        return BindingBuilder.bind(gradingQueue).to(gradingExchange).with(ROUTING);
    }
}
