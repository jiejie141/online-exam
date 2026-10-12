package com.jiejie.exam.grading;

/**
 * 这份答卷已经被判过分了 —— 本次判分是重复的，什么都不用做。
 *
 * <p><b>为什么它必须和 {@code BusinessException} 分开</b>：
 * 两者在消费者眼里要做的事完全相反。
 *
 * <ul>
 *   <li>{@code BusinessException}（"答卷不存在"、"尚未交卷"）—— <b>出错了</b>。
 *       正常情况下不该出现，出现即意味着有卷子会永远没分，应该被看见。</li>
 *   <li>本异常（"已被判分"）—— <b>完全正常</b>。MQ 至少一次投递 + 定时任务兜底，
 *       重复判分是设计内的预期行为，不是故障。</li>
 * </ul>
 *
 * <p>在此之前两者共用 {@code BusinessException}，消费者按类型一个 {@code catch} 全吞了，
 * 于是「并发下的正常重复」和「真的判不了分」在日志里长得一模一样 ——
 * 后者永远不会有人发现。把类型分开，是让这个区别能落到代码上的最小代价。
 *
 * <p>不继承 {@code BusinessException}：它不该被 {@code GlobalExceptionHandler}
 * 映射成 HTTP 状态码 —— 它根本不该出现在请求线程里，只出现在 MQ 消费者里。
 */
public class AlreadyGradedException extends RuntimeException {

    private final Long recordId;

    public AlreadyGradedException(Long recordId) {
        super("答卷已被判分，忽略本次重复判分: " + recordId);
        this.recordId = recordId;
    }

    public Long recordId() {
        return recordId;
    }
}
