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

import java.time.LocalDateTime;
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

    /** 一条「合法」的单选：选项 A–D 齐全、答案在选项内。夹具必须是合法数据，
     *  否则测出来的失败是夹具自己造的，不是被测代码的问题。 */
    private static final String SINGLE_OPTIONS =
            "[{\"key\":\"A\",\"text\":\"甲\"},{\"key\":\"B\",\"text\":\"乙\"},"
            + "{\"key\":\"C\",\"text\":\"丙\"},{\"key\":\"D\",\"text\":\"丁\"}]";

    private static Question questionOwnedBy(Long createdBy, long id) {
        Question q = new Question();
        q.setId(id);
        q.setSubject("Java");
        q.setType(QuestionType.SINGLE.code());
        q.setStem("下列哪个是接口？");
        q.setOptions(SINGLE_OPTIONS);
        q.setAnswer("A");
        q.setScore(10);
        q.setCreatedBy(createdBy);
        return q;
    }

    @Test
    void 不能把别人的题目加进自己的卷子() {
        when(examMapper.selectById(EXAM_ID)).thenReturn(examOwnedBy(TEACHER, ExamStatus.DRAFT));
        when(questionMapper.selectById(9L)).thenReturn(questionOwnedBy(OTHER_TEACHER, 9L));

        BusinessException e = assertThrows(BusinessException.class,
                () -> examService.addQuestions(EXAM_ID, List.of(9L), null, TEACHER));

        assertEquals(403, e.code());
        // 卷子是自己的、题目不是自己的 —— 只判前者会放它进来
        verify(examQuestionMapper, never()).insert(any(ExamQuestion.class));
    }

    @Test
    void 列表里夹着别人的题目就一道都不写() {
        when(examMapper.selectById(EXAM_ID)).thenReturn(examOwnedBy(TEACHER, ExamStatus.DRAFT));
        when(questionMapper.selectById(1L)).thenReturn(questionOwnedBy(TEACHER, 1L));
        when(questionMapper.selectById(9L)).thenReturn(questionOwnedBy(OTHER_TEACHER, 9L));

        assertThrows(BusinessException.class,
                () -> examService.addQuestions(EXAM_ID, List.of(1L, 9L), null, TEACHER));

        // 这个方法没有事务，能不能"全不写"完全取决于先校验后写入的顺序。
        // 边校验边写的话，1 号题已经被写进去了，卷子成了改一半的半成品。
        verify(examQuestionMapper, never()).insert(any(ExamQuestion.class));
    }

    @Test
    void 自己的题目正常加进卷子() {
        when(examMapper.selectById(EXAM_ID)).thenReturn(examOwnedBy(TEACHER, ExamStatus.DRAFT));
        when(questionMapper.selectById(1L)).thenReturn(questionOwnedBy(TEACHER, 1L));
        when(examQuestionMapper.insert(any(ExamQuestion.class))).thenReturn(1);

        assertEquals(1, examService.addQuestions(EXAM_ID, List.of(1L), 20, TEACHER));

        ArgumentCaptor<ExamQuestion> captor = ArgumentCaptor.forClass(ExamQuestion.class);
        verify(examQuestionMapper).insert(captor.capture());
        assertEquals(20, captor.getValue().getScore());  // 手动指定的分值优先于题目默认分值
    }

    @Test
    void 没有出题老师的题目拒绝入库() {
        Question q = questionOf("single", 10, 1);
        q.setCreatedBy(null);

        BusinessException e = assertThrows(BusinessException.class,
                () -> examService.createQuestion(q));

        assertEquals(400, e.code());
        // 存成 NULL 的题目从此谁都看不见 —— 等于写进黑洞，必须在入口挡住
        verify(questionMapper, never()).insert(any(Question.class));
    }

    @Test
    void 出题老师的id原样落库() {
        Question q = questionOf("single", 10, 1);
        when(questionMapper.insert(any(Question.class))).thenAnswer(inv -> {
            inv.getArgument(0, Question.class).setId(7L);
            return 1;
        });

        assertEquals(7L, examService.createQuestion(q));
    }

    // ------------------------------------------------------------------
    // 参数校验：这几条都是「传错了参数」，必须回 400，不能回 500，
    // 更不能把一条永远判不了分的题放进题库。
    // ------------------------------------------------------------------

    private static Question questionOf(String type, Integer score, Integer difficulty) {
        Question q = new Question();
        q.setSubject("Java");
        q.setType(type);
        q.setStem("下列哪个是接口？");
        // 判断题在库里 options 必须是 NULL；选择题必须有选项且答案在选项内。
        q.setOptions("judge".equals(type) ? null : SINGLE_OPTIONS);
        q.setAnswer("judge".equals(type) ? "T" : "A");
        q.setScore(score);
        q.setDifficulty(difficulty);
        q.setCreatedBy(TEACHER);
        return q;
    }

    @Test
    void 非法题型回400而不是500() {
        // QuestionType.of 抛的是 IllegalArgumentException，没人接的话会掉进
        // GlobalExceptionHandler 的 Exception 兜底变成 500「服务内部错误」。
        // 可这是调用方传错了参数，重试一万次也不会成功 —— 必须 4xx。
        BusinessException e = assertThrows(BusinessException.class,
                () -> examService.createQuestion(questionOf("fill-blank", 10, 1)));

        assertEquals(400, e.code());
        verify(questionMapper, never()).insert(any(Question.class));
    }

    @Test
    void 分值为负数的题目拒绝入库() {
        // 负分值的题能建出来，但它永远判不了分：GradingRule 第一行就抛
        // "fullScore must not be negative"，消费端记一条 error 后放弃，
        // 答卷永远停在「还没判完」。必须在入口挡住。
        BusinessException e = assertThrows(BusinessException.class,
                () -> examService.createQuestion(questionOf("single", -10, 1)));

        assertEquals(400, e.code());
        assertEquals("题目分值必须大于 0", e.getMessage());
        verify(questionMapper, never()).insert(any(Question.class));
    }

    @Test
    void 分值为0的题目也拒绝入库() {
        // 0 分题判出来是 0 分，不算错，但它在卷面上等于白占一道题的位置，
        // 而且 publish 累加总分时会让老师误以为卷面总分不对。同样拒绝。
        BusinessException e = assertThrows(BusinessException.class,
                () -> examService.createQuestion(questionOf("single", 0, 1)));

        assertEquals(400, e.code());
        verify(questionMapper, never()).insert(any(Question.class));
    }

    @Test
    void 分值没填的题目拒绝入库() {
        // 不填分值时 Controller 会补默认 5 分，所以这里为 null 只可能是
        // 别的调用方（或将来改坏了）。宁可报错，也别存一条 score 为 NULL 的题。
        BusinessException e = assertThrows(BusinessException.class,
                () -> examService.createQuestion(questionOf("single", null, 1)));

        assertEquals(400, e.code());
        verify(questionMapper, never()).insert(any(Question.class));
    }

    @Test
    void 难度为负数的题目拒绝入库() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> examService.createQuestion(questionOf("single", 10, -3)));

        assertEquals(400, e.code());
        verify(questionMapper, never()).insert(any(Question.class));
    }

    @Test
    void 正常题目照样能建() {
        // 反向断言：上面五条挡的是坏输入，不能顺手把好输入也挡了。
        Question q = questionOf("single", 10, 2);
        when(questionMapper.insert(any(Question.class))).thenAnswer(inv -> {
            inv.getArgument(0, Question.class).setId(11L);
            return 1;
        });

        assertEquals(11L, examService.createQuestion(q));
    }

    @Test
    void 及格线为负数时拒绝建考试() {
        // publish 只判「及格线高于卷面总分」，负及格线永远小于总分，
        // 反而一路畅通 —— 建出来的考试等于全员及格。
        LocalDateTime start = LocalDateTime.of(2026, 11, 1, 9, 0);
        BusinessException e = assertThrows(BusinessException.class,
                () -> examService.createExam("月考", "Java", start, start.plusHours(2), 90, -100, TEACHER));

        assertEquals(400, e.code());
        assertEquals("及格线不能是负数", e.getMessage());
        verify(examMapper, never()).insert(any(Exam.class));
    }

    @Test
    void 组卷时把分值覆盖成负数也要拒绝() {
        when(examMapper.selectById(EXAM_ID)).thenReturn(examOwnedBy(TEACHER, ExamStatus.DRAFT));

        BusinessException e = assertThrows(BusinessException.class,
                () -> examService.addQuestions(EXAM_ID, List.of(1L), -7, TEACHER));

        assertEquals(400, e.code());
        assertEquals("卷面分值必须大于 0", e.getMessage());
        // 一行都不能写进去
        verify(examQuestionMapper, never()).insert(any(ExamQuestion.class));
    }

    @Test
    void 题库里分值就是负数的脏题不能进卷子() {
        // 这是历史脏数据路径：不传 score 时沿用题库里的分值，
        // 而题库里可能已经有 score=-10 的题（修复之前建出来的）。
        Question dirty = questionOwnedBy(TEACHER, 5L);
        dirty.setScore(-10);
        when(examMapper.selectById(EXAM_ID)).thenReturn(examOwnedBy(TEACHER, ExamStatus.DRAFT));
        when(questionMapper.selectById(5L)).thenReturn(dirty);

        BusinessException e = assertThrows(BusinessException.class,
                () -> examService.addQuestions(EXAM_ID, List.of(5L), null, TEACHER));

        assertEquals(400, e.code());
        verify(examQuestionMapper, never()).insert(any(ExamQuestion.class));
    }
}
