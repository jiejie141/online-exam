package com.jiejie.exam.exam;

import com.jiejie.exam.cache.PaperCache;
import com.jiejie.exam.domain.entity.Exam;
import com.jiejie.exam.domain.entity.ExamQuestion;
import com.jiejie.exam.domain.entity.Question;
import com.jiejie.exam.domain.mapper.ExamMapper;
import com.jiejie.exam.domain.mapper.ExamQuestionMapper;
import com.jiejie.exam.domain.mapper.QuestionMapper;
import com.jiejie.exam.web.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 老师端「只能动自己的东西」的单元测试。
 *
 * <p>这里用的是<b>真的</b> {@link ExamAccess}，不是 mock —— 要测的正是
 * ExamService 确实走了那道闸门。把闸门 mock 掉，测出来的只是
 * 「我调了这个方法」，而不是「别人的考试进不来」。
 */
@ExtendWith(MockitoExtension.class)
class ExamServiceTest {

    private static final long TEACHER = 1L;
    private static final long OTHER_TEACHER = 2L;
    private static final long EXAM_ID = 100L;

    @Mock
    private ExamMapper examMapper;
    @Mock
    private ExamQuestionMapper examQuestionMapper;
    @Mock
    private QuestionMapper questionMapper;
    @Mock
    private PaperCache paperCache;

    private ExamService examService;

    @BeforeEach
    void setUp() {
        examService = new ExamService(examMapper, examQuestionMapper, questionMapper, paperCache,
                new ExamAccess(examMapper));
    }

    private Exam examOwnedBy(Long createdBy, ExamStatus status) {
        Exam e = new Exam();
        e.setId(EXAM_ID);
        e.setTitle("Java 后端基础月考");
        e.setStatus(status.code());
        e.setPassScore(60);
        e.setCreatedBy(createdBy);
        return e;
    }

    private static ExamQuestion item(int score) {
        ExamQuestion eq = new ExamQuestion();
        eq.setExamId(EXAM_ID);
        eq.setScore(score);
        return eq;
    }

    @Test
    void 别人不能往他人卷子里加题() {
        when(examMapper.selectById(EXAM_ID)).thenReturn(examOwnedBy(OTHER_TEACHER, ExamStatus.DRAFT));

        BusinessException e = assertThrows(BusinessException.class,
                () -> examService.addQuestions(EXAM_ID, List.of(1L), 10, TEACHER));

        assertEquals(403, e.code());
        // 不是「抛了异常就算过」：必须确认一行都没写进去
        verify(examQuestionMapper, never()).insert(any(ExamQuestion.class));
    }

    @Test
    void 别人不能发布他人的考试() {
        when(examMapper.selectById(EXAM_ID)).thenReturn(examOwnedBy(OTHER_TEACHER, ExamStatus.DRAFT));

        BusinessException e = assertThrows(BusinessException.class,
                () -> examService.publish(EXAM_ID, TEACHER));

        assertEquals(403, e.code());
        verify(examMapper, never()).updateById(any(Exam.class));
        verify(paperCache, never()).evict(any());
    }

    @Test
    void 别人不能结束他人的考试() {
        when(examMapper.selectById(EXAM_ID)).thenReturn(examOwnedBy(OTHER_TEACHER, ExamStatus.PUBLISHED));

        BusinessException e = assertThrows(BusinessException.class,
                () -> examService.close(EXAM_ID, TEACHER));

        assertEquals(403, e.code());
        verify(examMapper, never()).updateById(any(Exam.class));
    }

    @Test
    void 自己的考试可以发布且总分按卷面累加() {
        when(examMapper.selectById(EXAM_ID)).thenReturn(examOwnedBy(TEACHER, ExamStatus.DRAFT));
        when(examQuestionMapper.selectList(any())).thenReturn(List.of(item(50), item(50)));

        examService.publish(EXAM_ID, TEACHER);

        ArgumentCaptor<Exam> captor = ArgumentCaptor.forClass(Exam.class);
        verify(examMapper).updateById(captor.capture());
        Exam saved = captor.getValue();
        // 总分是卷面实际分值加出来的，不是老师手填的
        assertEquals(100, saved.getTotalScore());
        assertEquals(ExamStatus.PUBLISHED.code(), saved.getStatus());
        // 发布后卷子固定，必须清缓存，否则学生可能拉到发布前的旧卷
        verify(paperCache).evict(EXAM_ID);
    }

    @Test
    void 及格线高于卷面总分时拒绝发布() {
        Exam exam = examOwnedBy(TEACHER, ExamStatus.DRAFT);
        exam.setPassScore(150);
        when(examMapper.selectById(EXAM_ID)).thenReturn(exam);
        when(examQuestionMapper.selectList(any())).thenReturn(List.of(item(50), item(50)));

        BusinessException e = assertThrows(BusinessException.class,
                () -> examService.publish(EXAM_ID, TEACHER));

        assertEquals(400, e.code());
        verify(examMapper, never()).updateById(any(Exam.class));
        verify(paperCache, never()).evict(any());
    }

    @Test
    void 卷子没有题目时拒绝发布() {
        when(examMapper.selectById(EXAM_ID)).thenReturn(examOwnedBy(TEACHER, ExamStatus.DRAFT));
        when(examQuestionMapper.selectList(any())).thenReturn(List.of());

        BusinessException e = assertThrows(BusinessException.class,
                () -> examService.publish(EXAM_ID, TEACHER));

        assertEquals(400, e.code());
        verify(paperCache, never()).evict(any());
    }

    @Test
    void 没有出题老师的题目拒绝入库() {
        Question q = new Question();
        q.setSubject("Java");
        q.setType(QuestionType.SINGLE.code());
        q.setStem("下列哪个是接口？");
        q.setAnswer("A");
        q.setScore(10);
        q.setCreatedBy(null);

        BusinessException e = assertThrows(BusinessException.class,
                () -> examService.createQuestion(q));

        assertEquals(400, e.code());
        // 存成 NULL 的题目从此谁都看不见 —— 等于写进黑洞，必须在入口挡住
        verify(questionMapper, never()).insert(any(Question.class));
    }

    @Test
    void 出题老师的id原样落库() {
        Question q = new Question();
        q.setSubject("Java");
        q.setType(QuestionType.SINGLE.code());
        q.setStem("下列哪个是接口？");
        q.setAnswer("A");
        q.setScore(10);
        q.setCreatedBy(TEACHER);
        when(questionMapper.insert(any(Question.class))).thenAnswer(inv -> {
            inv.getArgument(0, Question.class).setId(7L);
            return 1;
        });

        assertEquals(7L, examService.createQuestion(q));
    }
}
