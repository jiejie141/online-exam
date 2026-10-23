package com.jiejie.exam.exam;

import com.jiejie.exam.domain.entity.Exam;
import com.jiejie.exam.domain.mapper.ExamMapper;
import com.jiejie.exam.web.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

/**
 * 归属判定的单元测试。
 *
 * <p>这一层单独测，是因为它是老师端七个入口共用的那道闸门：
 * 闸门本身错了，后面每个入口都会错，而错法是「静默放行」——
 * 接口照样返回 200，只是返回的已经不是自己的东西。
 */
@ExtendWith(MockitoExtension.class)
class ExamAccessTest {

    private static final long TEACHER = 1L;
    private static final long OTHER_TEACHER = 2L;
    private static final long EXAM_ID = 100L;

    @Mock
    private ExamMapper examMapper;

    @InjectMocks
    private ExamAccess examAccess;

    private static Exam exam(Long createdBy) {
        Exam e = new Exam();
        e.setId(EXAM_ID);
        e.setTitle("Java 后端基础月考");
        e.setCreatedBy(createdBy);
        return e;
    }

    @Test
    void 自己的考试返回原对象() {
        Exam mine = exam(TEACHER);
        when(examMapper.selectById(EXAM_ID)).thenReturn(mine);

        // 返回同一个实例，不是复制品：调用方通常还要接着读它的状态
        assertSame(mine, examAccess.requireOwned(EXAM_ID, TEACHER));
    }

    @Test
    void 别人的考试抛403而不是404() {
        when(examMapper.selectById(EXAM_ID)).thenReturn(exam(OTHER_TEACHER));

        BusinessException e = assertThrows(BusinessException.class,
                () -> examAccess.requireOwned(EXAM_ID, TEACHER));

        // 403 而不是 404：考试确实存在，只是不属于你。
        // 用 404 会把「不存在」和「不是你的」混成同一个回答。
        assertEquals(403, e.code());
    }

    @Test
    void 不存在的考试抛400() {
        when(examMapper.selectById(EXAM_ID)).thenReturn(null);

        BusinessException e = assertThrows(BusinessException.class,
                () -> examAccess.requireOwned(EXAM_ID, TEACHER));

        assertEquals(400, e.code());
    }

    @Test
    void created_by为空的考试拒绝操作而不是放行() {
        // fail-closed：无主数据的正确修法是回填 created_by，
        // 不是让所有老师都能改一场谁都不负责的考试
        when(examMapper.selectById(EXAM_ID)).thenReturn(exam(null));

        BusinessException e = assertThrows(BusinessException.class,
                () -> examAccess.requireOwned(EXAM_ID, TEACHER));

        assertEquals(403, e.code());
    }
}
