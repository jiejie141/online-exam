package com.jiejie.exam.grading;

import com.jiejie.exam.web.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 消费者对三类结局的处理必须不同 —— 这组测试钉的就是"不同"这两个字。
 *
 * <p>改动之前，{@code BusinessException} 把"并发下的正常重复"和"真的判不了分"
 * 混成了一个类型，消费者一个 catch 全吞成 warn。后果不是崩溃，是<b>静默</b>：
 * 一份永远判不了分的卷子，和一次正常的重复投递，在日志里长得一模一样。
 *
 * <p>所以这里逐个断言：正常重复不进失败指标，真失败必须进。
 * 光断言"没抛异常"是不够的 —— 原来的代码也不抛。
 */
@ExtendWith(MockitoExtension.class)
class GradingConsumerTest {

    @Mock
    private GradingService gradingService;
    @Mock
    private GradingMetrics metrics;

    @InjectMocks
    private GradingConsumer consumer;

    @Test
    void 正常判分记成功不记失败() {
        when(gradingService.grade(100L)).thenReturn(87);

        consumer.onGradingMessage(100L);

        verify(metrics, never()).gradingFailed();
    }

    @Test
    void 并发下的重复判分不算失败() {
        // 这是 MQ 至少一次投递 + 定时任务兜底共同导致的**预期**行为，不是故障。
        // 把它计进失败指标，指标就会被噪声填满，真正卡住的卷子反而被淹掉。
        when(gradingService.grade(100L)).thenThrow(new AlreadyGradedException(100L));

        assertDoesNotThrow(() -> consumer.onGradingMessage(100L));

        verify(metrics, never()).gradingFailed();
    }

    @Test
    void 答卷不存在属于真失败要计入指标() {
        // BusinessException 现在只承载"真出错"这一种语义
        when(gradingService.grade(100L)).thenThrow(new BusinessException("答卷不存在: 100"));

        assertDoesNotThrow(() -> consumer.onGradingMessage(100L));

        verify(metrics).gradingFailed();
    }

    @Test
    void 未预期异常也计入指标() {
        when(gradingService.grade(100L)).thenThrow(new IllegalStateException("连接池断了"));

        assertDoesNotThrow(() -> consumer.onGradingMessage(100L));

        verify(metrics).gradingFailed();
    }

    @Test
    void 任何异常都不抛回Broker() {
        // 抛回去会让消息无限重投把消费者打满 —— 这是消费者吞异常的原因，
        // 改成"分开记"之后这个保证不能丢。
        when(gradingService.grade(100L)).thenThrow(new RuntimeException("boom"));

        assertDoesNotThrow(() -> consumer.onGradingMessage(100L));
    }
}
