package com.jiejie.exam.stats;

import com.jiejie.exam.domain.dto.ExamScoreStats;
import com.jiejie.exam.domain.entity.Exam;
import com.jiejie.exam.domain.mapper.ExamAnswerMapper;
import com.jiejie.exam.domain.mapper.ExamMapper;
import com.jiejie.exam.domain.mapper.ExamRecordMapper;
import com.jiejie.exam.exam.ExamAccess;
import com.jiejie.exam.exam.RecordStatus;
import com.jiejie.exam.web.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 成绩统计的归属校验 + 空数据口径。
 *
 * <p>统计接口比别人更敏感：它把一场考试所有人的分数、每题正确率都摊开，
 * 而这本来只该给出题的那位老师看。改动前它只判「考试存在」，
 * 也就是任何老师知道 examId 就能看别人那场考试的全班成绩。
 */
@ExtendWith(MockitoExtension.class)
class StatsServiceTest {

    private static final long TEACHER = 1L;
    private static final long OTHER_TEACHER = 2L;
    private static final long EXAM_ID = 100L;

    @Mock
    private ExamRecordMapper recordMapper;
    @Mock
    private ExamAnswerMapper answerMapper;
    @Mock
    private ExamMapper examMapper;

    private StatsService statsService;

    @BeforeEach
    void setUp() {
        statsService = new StatsService(recordMapper, answerMapper, new ExamAccess(examMapper));
    }

    private Exam examOwnedBy(Long createdBy) {
        Exam e = new Exam();
        e.setId(EXAM_ID);
        e.setTitle("Java 后端基础月考");
        e.setCreatedBy(createdBy);
        return e;
    }

    @Test
    void 别人看不到他人考试的成绩概览() {
        when(examMapper.selectById(EXAM_ID)).thenReturn(examOwnedBy(OTHER_TEACHER));

        BusinessException e = assertThrows(BusinessException.class,
                () -> statsService.stats(EXAM_ID, TEACHER));

        assertEquals(403, e.code());
        verify(recordMapper, never()).stats(EXAM_ID);
    }

    @Test
    void 别人看不到他人考试的成绩分布() {
        when(examMapper.selectById(EXAM_ID)).thenReturn(examOwnedBy(OTHER_TEACHER));

        BusinessException e = assertThrows(BusinessException.class,
                () -> statsService.distribution(EXAM_ID, TEACHER));

        assertEquals(403, e.code());
        verify(recordMapper, never()).distribution(EXAM_ID);
    }

    @Test
    void 别人看不到他人考试的逐题正确率() {
        when(examMapper.selectById(EXAM_ID)).thenReturn(examOwnedBy(OTHER_TEACHER));

        BusinessException e = assertThrows(BusinessException.class,
                () -> statsService.accuracy(EXAM_ID, TEACHER));

        assertEquals(403, e.code());
        verify(answerMapper, never()).accuracyByExam(EXAM_ID);
    }

    @Test
    void 别人看不到他人考试的交卷进度() {
        when(examMapper.selectById(EXAM_ID)).thenReturn(examOwnedBy(OTHER_TEACHER));

        BusinessException e = assertThrows(BusinessException.class,
                () -> statsService.progress(EXAM_ID, TEACHER));

        assertEquals(403, e.code());
        verify(recordMapper, never()).countByExamAndStatus(EXAM_ID, RecordStatus.ONGOING.code());
    }

    @Test
    void 没人判分时概览补0而不是空指针() {
        when(examMapper.selectById(EXAM_ID)).thenReturn(examOwnedBy(TEACHER));
        // 没人交卷时这条 SQL 仍然返回一行：COUNT 是 0，AVG/MAX/MIN 是 NULL
        when(recordMapper.stats(EXAM_ID)).thenReturn(null);

        ExamScoreStats s = statsService.stats(EXAM_ID, TEACHER);

        assertEquals(0, s.getTotal());
        assertEquals(0, s.getPassCount());
        // 「平均分 0」是假数据，会显示成一个真实存在的 0 分；保持 null 让前端按 total 判断
        assertNull(s.getAvgScore());
        assertNull(s.getMaxScore());
        assertNull(s.getMinScore());
    }

    @Test
    void 交卷进度把已交和已判都算作已交() {
        when(examMapper.selectById(EXAM_ID)).thenReturn(examOwnedBy(TEACHER));
        when(recordMapper.countByExamAndStatus(EXAM_ID, RecordStatus.SUBMITTED.code())).thenReturn(3L);
        when(recordMapper.countByExamAndStatus(EXAM_ID, RecordStatus.GRADED.code())).thenReturn(2L);
        when(recordMapper.countByExamAndStatus(EXAM_ID, RecordStatus.ONGOING.code())).thenReturn(5L);

        StatsService.Progress p = statsService.progress(EXAM_ID, TEACHER);

        // 判分只是时间问题，「已交」必须把 graded 也算进去，
        // 否则判完分的学生会从「已交」里消失，进度条倒退
        assertEquals(5, p.submitted());
        assertEquals(5, p.ongoing());
    }
}
