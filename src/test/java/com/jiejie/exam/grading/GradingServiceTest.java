package com.jiejie.exam.grading;

import com.jiejie.exam.domain.entity.ExamAnswer;
import com.jiejie.exam.domain.entity.ExamRecord;
import com.jiejie.exam.domain.mapper.ExamAnswerMapper;
import com.jiejie.exam.domain.mapper.ExamQuestionMapper;
import com.jiejie.exam.domain.mapper.ExamRecordMapper;
import com.jiejie.exam.exam.RecordStatus;
import com.jiejie.exam.web.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GradingServiceTest {

    @Mock
    private ExamRecordMapper recordMapper;
    @Mock
    private ExamAnswerMapper answerMapper;
    @Mock
    private ExamQuestionMapper examQuestionMapper;

    @InjectMocks
    private GradingService gradingService;

    /** 构造 answer key 的一行。键必须是 snake_case —— SQL 别名是什么，这里就是什么 */
    private static Map<String, Object> keyRow(long questionId, String type, String answer, int score) {
        return Map.of("question_id", questionId, "type", type, "answer", answer, "score", score);
    }

    private static ExamRecord record(String status, Integer score) {
        ExamRecord r = new ExamRecord();
        r.setId(100L);
        r.setExamId(1L);
        r.setUserId(7L);
        r.setStatus(status);
        r.setScore(score);
        return r;
    }

    private static ExamAnswer answer(long id, long questionId, String userAnswer) {
        ExamAnswer a = new ExamAnswer();
        a.setId(id);
        a.setQuestionId(questionId);
        a.setUserAnswer(userAnswer);
        return a;
    }

    @Test
    void 单选答对加多选漏选的总分是满分加半分() {
        when(recordMapper.selectById(100L)).thenReturn(record(RecordStatus.SUBMITTED.code(), null));
        when(examQuestionMapper.selectAnswerKey(1L)).thenReturn(List.of(
                keyRow(1L, "single", "A", 5),
                keyRow(2L, "multiple", "ABD", 10)));
        when(answerMapper.listByRecord(100L)).thenReturn(List.of(
                answer(11L, 1L, "A"),
                answer(12L, 2L, "AB")));
        when(recordMapper.markGraded(100L, 10)).thenReturn(1);

        int score = gradingService.grade(100L);

        assertEquals(10, score);
        verify(answerMapper).updateGrade(11L, true, 5);
        verify(answerMapper).updateGrade(12L, false, 5); // 漏选：给半分但不算对
    }

    @Test
    void 已判分的答卷重复判分直接返回原分数() {
        when(recordMapper.selectById(100L)).thenReturn(record(RecordStatus.GRADED.code(), 88));

        assertEquals(88, gradingService.grade(100L));

        verify(answerMapper, never()).updateGrade(anyLong(), anyBoolean(), anyInt());
        verify(recordMapper, never()).markGraded(anyLong(), anyInt());
    }

    @Test
    void 未交卷的答卷不能判分() {
        when(recordMapper.selectById(100L)).thenReturn(record(RecordStatus.ONGOING.code(), null));

        assertThrows(BusinessException.class, () -> gradingService.grade(100L));
    }

    @Test
    void 并发下markGraded返回0说明别人先判完了要抛AlreadyGraded让事务回滚() {
        when(recordMapper.selectById(100L)).thenReturn(record(RecordStatus.SUBMITTED.code(), null));
        when(examQuestionMapper.selectAnswerKey(1L)).thenReturn(List.of(keyRow(1L, "single", "A", 5)));
        when(answerMapper.listByRecord(100L)).thenReturn(List.of(answer(11L, 1L, "A")));
        when(recordMapper.markGraded(100L, 5)).thenReturn(0);

        // 事务仍要回滚（exam_answer 不能被写两遍），但类型是 AlreadyGraded 而非
        // BusinessException —— 消费者要靠类型区分"正常重复"和"真失败"，
        // 混用会让后者被静默吞掉。见 AlreadyGradedException 的类注释。
        assertThrows(AlreadyGradedException.class, () -> gradingService.grade(100L));
    }

    @Test
    void 已被移出卷子的题不参与计分() {
        when(recordMapper.selectById(100L)).thenReturn(record(RecordStatus.SUBMITTED.code(), null));
        when(examQuestionMapper.selectAnswerKey(1L)).thenReturn(List.of(keyRow(1L, "single", "A", 5)));
        when(answerMapper.listByRecord(100L)).thenReturn(List.of(
                answer(11L, 1L, "A"),
                answer(12L, 99L, "A")));
        when(recordMapper.markGraded(eq(100L), eq(5))).thenReturn(1);

        assertEquals(5, gradingService.grade(100L));

        verify(answerMapper).updateGrade(11L, true, 5);
        verify(answerMapper, never()).updateGrade(eq(12L), anyBoolean(), anyInt());
    }

    @Test
    void 全卷未作答得0分() {
        when(recordMapper.selectById(100L)).thenReturn(record(RecordStatus.SUBMITTED.code(), null));
        when(examQuestionMapper.selectAnswerKey(1L)).thenReturn(List.of(keyRow(1L, "single", "A", 5)));
        when(answerMapper.listByRecord(100L)).thenReturn(List.of());
        when(recordMapper.markGraded(100L, 0)).thenReturn(1);

        assertEquals(0, gradingService.grade(100L));
    }
}
