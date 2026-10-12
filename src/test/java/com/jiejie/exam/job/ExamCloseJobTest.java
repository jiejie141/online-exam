package com.jiejie.exam.job;

import com.jiejie.exam.domain.entity.Exam;
import com.jiejie.exam.domain.mapper.ExamMapper;
import com.jiejie.exam.exam.ExamStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 关考任务：把时间窗已过、状态还挂着 published 的考试置为 closed。
 *
 * <p>它和收卷是两件事（见类的 javadoc）。这里主要钉住一件事：
 * **一场更新失败不能让这一轮剩下的考试全被跳过。**
 */
@ExtendWith(MockitoExtension.class)
class ExamCloseJobTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 12, 10, 0);
    private static final Clock FIXED =
            Clock.fixed(NOW.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());

    @Mock
    private ExamMapper examMapper;

    private ExamCloseJob job;

    private Exam exam(long id, String title) {
        Exam e = new Exam();
        e.setId(id);
        e.setTitle(title);
        e.setStatus(ExamStatus.PUBLISHED.code());
        return e;
    }

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        job = new ExamCloseJob(examMapper, FIXED);
    }

    @Test
    void 把到点的考试置为closed() {
        Exam e = exam(1L, "月考");
        when(examMapper.findPublishedEndedBefore(NOW)).thenReturn(List.of(e));

        job.run();

        // 断言的是"写进去的那份状态"：任务是在同一个对象上改状态再 updateById 的
        assertEquals(ExamStatus.CLOSED.code(), e.getStatus());
        verify(examMapper).updateById(e);
    }

    @Test
    void 没有到点的考试就直接返回不查库外的东西() {
        when(examMapper.findPublishedEndedBefore(NOW)).thenReturn(List.of());

        job.run();

        verify(examMapper, never()).updateById(any(Exam.class));
    }

    @Test
    void 一场更新失败不能跳过剩下的考试() {
        // 这是这个测试存在的理由：没有 try/catch 时，第一场抛异常会让
        // for 循环整体中断，后面几场这一轮全关不掉（下轮才补）。
        // 而且日志里只会看到"抛了异常"，看不出"少关了几场"。
        Exam bad = exam(1L, "会失败的");
        Exam good = exam(2L, "应该被关掉");
        when(examMapper.findPublishedEndedBefore(NOW)).thenReturn(List.of(bad, good));
        when(examMapper.updateById(bad)).thenThrow(new RuntimeException("锁等待超时"));

        assertDoesNotThrow(() -> job.run());

        // 关键断言：第二场仍然被处理了
        verify(examMapper).updateById(good);
    }
}
