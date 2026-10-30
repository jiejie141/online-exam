package com.jiejie.exam.exam;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jiejie.exam.domain.entity.Exam;
import com.jiejie.exam.domain.entity.Question;
import com.jiejie.exam.domain.mapper.ExamMapper;
import com.jiejie.exam.domain.mapper.QuestionMapper;
import com.jiejie.exam.web.BusinessException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 自动组卷。
 *
 * <p>这里用的 {@link ExamAccess} 是<b>真的</b>（只把它的 mapper mock 掉），
 * 要测的正是"抽题没有绕过归属校验"。把闸门 mock 掉，测出来的只是
 * "我调了这个方法"，而不是"别人的考试抽不了题"。
 */
@ExtendWith(MockitoExtension.class)
class AutoComposeServiceTest {

    private static final long TEACHER = 1L;
    private static final long EXAM = 100L;

    @Mock
    private QuestionMapper questionMapper;
    @Mock
    private ExamMapper examMapper;
    @Mock
    private ExamService examService;

    private AutoComposeService service;

    @BeforeEach
    void setUp() {
        service = new AutoComposeService(questionMapper, new ExamAccess(examMapper), examService);
    }

    /** 同 {@code QuestionImportServiceTest}：LambdaQueryWrapper 需要手工填 TableInfo 缓存 */
    @BeforeAll
    static void initLambdaCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                Question.class);
    }

    private Exam exam(String status, String subject) {
        Exam e = new Exam();
        e.setId(EXAM);
        e.setCreatedBy(TEACHER);
        e.setStatus(status);
        e.setSubject(subject);
        return e;
    }

    private void poolIs(Long... ids) {
        // selectObjs 的签名是 <E> List<E>，服务里按 List<Object> 接。
        // 这里构造同样的形状，别让测试通过了一个生产中不可能出现的类型
        List<Object> raw = new ArrayList<>();
        for (Long id : ids) {
            raw.add(id);
        }
        when(questionMapper.selectObjs(any())).thenReturn(raw);
    }

    @SuppressWarnings("unchecked")
    private List<Long> capturedIds() {
        ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
        verify(examService).addQuestions(eq(EXAM), captor.capture(), any(), eq(TEACHER));
        return captor.getValue();
    }

    @Test
    void 按配额抽题并把id交给addQuestions() {
        when(examMapper.selectById(EXAM)).thenReturn(exam("draft", "Java"));
        poolIs(11L, 12L, 13L, 14L, 15L);
        when(examService.addQuestions(any(), any(), any(), any())).thenReturn(3);

        var r = service.compose(EXAM, null, List.of(new AutoComposeService.Quota("single", null, 3)), 5, TEACHER);

        assertEquals("Java", r.subject(), "不传 subject 时应当用这场考试的科目");
        assertEquals(3, capturedIds().size());
        assertEquals(3, r.added());
    }

    @Test
    void 题库不够时报出到底缺多少而不是悄悄少抽() {
        // 抽到 3 道也发布出去，卷面总分就和老师预期不符，
        // 而总分是 publish 时算出来的，错误会一路传到成绩统计
        when(examMapper.selectById(EXAM)).thenReturn(exam("draft", "Java"));
        poolIs(11L, 12L, 13L);

        BusinessException e = assertThrows(BusinessException.class, () ->
                service.compose(EXAM, null, List.of(new AutoComposeService.Quota("single", 2, 5)), 5, TEACHER));

        assertTrue(e.getMessage().contains("只有 3 道"), e.getMessage());
        assertTrue(e.getMessage().contains("只有 3 道可用"), e.getMessage());
        verify(examService, never()).addQuestions(any(), any(), any(), any());
    }

    @Test
    void 已发布的考试不能自动组卷() {
        when(examMapper.selectById(EXAM)).thenReturn(exam("published", "Java"));

        BusinessException e = assertThrows(BusinessException.class, () ->
                service.compose(EXAM, null, List.of(new AutoComposeService.Quota("single", null, 1)), 5, TEACHER));

        assertTrue(e.getMessage().contains("草稿"), e.getMessage());
    }

    @Test
    void 别人的考试抽不了题() {
        Exam other = exam("draft", "Java");
        other.setCreatedBy(999L);
        when(examMapper.selectById(EXAM)).thenReturn(other);

        BusinessException e = assertThrows(BusinessException.class, () ->
                service.compose(EXAM, null, List.of(new AutoComposeService.Quota("single", null, 1)), 5, TEACHER));

        assertEquals(403, e.code());
    }

    @Test
    void 两条配额不会抽到同一道题() {
        // "单选简单 2 道" + "单选不限难度 2 道" 是老师很自然会写的一种组合，
        // 两次查询会返回大量重叠的 id。同一道题在一张卷子上只能出现一次
        // （uk_exam_question），重复抽中的结果是被静默跳过，总数就少了
        when(examMapper.selectById(EXAM)).thenReturn(exam("draft", "Java"));
        poolIs(11L, 12L, 13L, 14L);
        when(examService.addQuestions(any(), any(), any(), any())).thenReturn(4);

        service.compose(EXAM, null, List.of(
                new AutoComposeService.Quota("single", 1, 2),
                new AutoComposeService.Quota("single", null, 2)), 5, TEACHER);

        List<Long> ids = capturedIds();
        assertEquals(4, ids.size());
        assertEquals(4, ids.stream().distinct().count(), "抽到的 id 不能重复");
    }

    @Test
    void 数量或难度不合法时立刻拒绝() {
        when(examMapper.selectById(EXAM)).thenReturn(exam("draft", "Java"));

        assertThrows(BusinessException.class, () ->
                service.compose(EXAM, null, List.of(new AutoComposeService.Quota("single", null, 0)), 5, TEACHER));

        assertThrows(BusinessException.class, () ->
                service.compose(EXAM, null, List.of(new AutoComposeService.Quota("single", 4, 1)), 5, TEACHER));

        assertThrows(BusinessException.class, () ->
                service.compose(EXAM, null, List.of(new AutoComposeService.Quota("essay", null, 1)), 5, TEACHER));

        assertThrows(BusinessException.class, () -> service.compose(EXAM, null, List.of(), 5, TEACHER));
    }

    @Test
    void 一次组卷总量有上限() {
        // 防"手滑填 10000"的护栏，不是技术限制
        when(examMapper.selectById(EXAM)).thenReturn(exam("draft", "Java"));

        BusinessException e = assertThrows(BusinessException.class, () ->
                service.compose(EXAM, null,
                        List.of(new AutoComposeService.Quota("single", null, AutoComposeService.MAX_TOTAL + 1)),
                        5, TEACHER));

        assertTrue(e.getMessage().contains("最多"), e.getMessage());
    }
}
