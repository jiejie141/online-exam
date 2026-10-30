package com.jiejie.exam.job;

import com.jiejie.exam.domain.mapper.ExamRecordMapper;
import com.jiejie.exam.grading.GradingMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 扫描任务只干一件事：把"交过卷但没分"的数量做成指标。
 *
 * <p>它不重投、不重判 —— 职责边界本身也要被测到，
 * 否则以后有人顺手在这里加个"自动重判"，就悄悄变成了另一件事。
 */
@ExtendWith(MockitoExtension.class)
class GradingSweepJobTest {

    @Mock
    private ExamRecordMapper recordMapper;
    @Mock
    private GradingMetrics metrics;

    private GradingSweepJob job;

    @BeforeEach
    void setUp() {
        // 真的 JobLock + 假 Redis：mock 掉锁等于把"任务有没有套锁"这件事也一起 mock 了
        job = new GradingSweepJob(recordMapper, metrics, JobLockTestSupport.lockThatAlwaysAcquires());
    }

    @Test
    void 把漏判数量刷新进指标() {
        when(recordMapper.countUnscored()).thenReturn(3L);

        job.run();

        verify(metrics).refreshUnscored(3L);
    }

    @Test
    void 没有漏判时也刷新为零() {
        // 关键：必须显式刷 0，否则指标会永远停在历史最高值 ——
        // 那样它就成了"历史上曾经积压过"而不是"此刻积压着"，
        // 看板会一直报警，人就不看了。
        when(recordMapper.countUnscored()).thenReturn(0L);

        job.run();

        verify(metrics).refreshUnscored(0L);
    }

    @Test
    void 扫描本身失败不能把调度线程搞死() {
        // fixedDelay 的下一轮还会来。这里让它抛出去，调度线程就死了，
        // 而且是**静默**死的 —— 之后这个指标再也不更新，没人知道。
        when(recordMapper.countUnscored()).thenThrow(new RuntimeException("库连不上"));

        assertDoesNotThrow(() -> job.run());
    }
}
