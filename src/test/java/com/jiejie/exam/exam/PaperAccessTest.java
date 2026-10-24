package com.jiejie.exam.exam;

import com.jiejie.exam.cache.PaperCache;
import com.jiejie.exam.domain.dto.PaperItem;
import com.jiejie.exam.domain.entity.Exam;
import com.jiejie.exam.domain.entity.ExamRecord;
import com.jiejie.exam.domain.mapper.ExamAnswerMapper;
import com.jiejie.exam.domain.mapper.ExamMapper;
import com.jiejie.exam.domain.mapper.ExamQuestionMapper;
import com.jiejie.exam.domain.mapper.ExamRecordMapper;
import com.jiejie.exam.grading.GradingProducer;
import com.jiejie.exam.grading.GradingService;
import com.jiejie.exam.web.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 取卷面的越权测试。
 *
 * <p>这一层单独一个测试类，因为它守的是一条和成绩、交卷都不一样的线：
 * 「卷子本身能不能被看到」。原来的 {@code paper(examId)} 只看 examId，
 * 任何登录用户（哪怕是别的班的学生）猜一个 id 就能把整场卷子拿走，
 * 包括还没开考的那场。考试系统里，题面提前泄露等于这场考试作废，
 * 比丢一份成绩严重得多。
 *
 * <p>判据是「有没有自己的答卷」：答卷只会在 {@code start()} 通过
 * 已发布 + 时间窗全开这两道校验之后才被创建出来。
 * 所以「我有这份答卷」本身就已经证明了当时是合法进场。
 */
@ExtendWith(MockitoExtension.class)
class PaperAccessTest {

    private static final long STUDENT = 7L;
    private static final long OTHER_STUDENT = 8L;
    private static final long EXAM_ID = 1L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 1, 9, 0, 0);
    private static final Clock FIXED_CLOCK =
            Clock.fixed(NOW.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());

    @Mock
    private ExamMapper examMapper;
    @Mock
    private ExamRecordMapper recordMapper;
    @Mock
    private ExamAnswerMapper answerMapper;
    @Mock
    private ExamQuestionMapper examQuestionMapper;
    @Mock
    private PaperCache paperCache;
    @Mock
    private GradingService gradingService;
    @Mock
    private GradingProducer gradingProducer;

    private ExamSessionService service;

    @BeforeEach
    void setUp() {
        service = new ExamSessionService(examMapper, recordMapper, answerMapper, examQuestionMapper,
                paperCache, gradingService, gradingProducer, FIXED_CLOCK, true);
    }

    private static PaperItem item(long id) {
        PaperItem p = new PaperItem();
        p.setId(id);   // PaperItem 是 @Data，没有链式 setter
        return p;
    }

    private static ExamRecord record(long userId) {
        ExamRecord r = new ExamRecord();
        r.setId(500L);
        r.setExamId(EXAM_ID);
        r.setUserId(userId);
        r.setStatus(RecordStatus.ONGOING.code());
        r.setStartedAt(NOW.minusMinutes(10));
        r.setDeadlineAt(NOW.plusMinutes(50));
        return r;
    }

    @Test
    void 没进过这场考试不能取卷面() {
        when(recordMapper.findByExamAndUser(EXAM_ID, STUDENT)).thenReturn(null);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.paperForStudent(EXAM_ID, STUDENT));

        assertEquals(403, e.code());
        // 关键：既不查库也不查缓存。否则说明"先拿到卷子再判断"，
        // 而缓存里那份卷子仍然可能被别的路径读到
        verify(examQuestionMapper, never()).selectPaper(anyLong());
        verify(paperCache, never()).get(any());
    }

    @Test
    void 进过之后可以取卷面() {
        when(recordMapper.findByExamAndUser(EXAM_ID, STUDENT)).thenReturn(record(STUDENT));
        PaperItem q1 = item(1L);
        when(paperCache.get(EXAM_ID)).thenReturn(List.of(q1));

        List<PaperItem> paper = service.paperForStudent(EXAM_ID, STUDENT);

        assertEquals(1, paper.size());
        assertEquals(1L, paper.get(0).getId());
    }

    @Test
    void 别人进过的考试不会因为id相同就对我开放() {
        // 同一场考试里另有别人的答卷；查的是"我的"，所以照样查不到
        when(recordMapper.findByExamAndUser(EXAM_ID, STUDENT)).thenReturn(null);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.paperForStudent(EXAM_ID, STUDENT));

        assertEquals(403, e.code());
    }

    @Test
    void 考试不存在和你没进过给同一个回答() {
        // 两种情况都是 record 查不到 —— 不区分它们，是为了不把
        // "哪些 examId 是真的" 变成一个可以扫的接口
        when(recordMapper.findByExamAndUser(999L, STUDENT)).thenReturn(null);

        BusinessException missing = assertThrows(BusinessException.class,
                () -> service.paperForStudent(999L, STUDENT));
        when(recordMapper.findByExamAndUser(EXAM_ID, STUDENT)).thenReturn(null);
        BusinessException notEntered = assertThrows(BusinessException.class,
                () -> service.paperForStudent(EXAM_ID, STUDENT));

        assertEquals(missing.code(), notEntered.code());
    }

    @Test
    void 进入考试自己会取卷面不需要先有答卷() {
        // start 走的是内部的 loadPaper：这时候答卷刚插进去（或正准备插），
        // 不能反过来要求"先有答卷才能取卷"，否则第一次进场必然拿不到题
        Exam exam = new Exam();
        exam.setId(EXAM_ID);
        exam.setStatus(ExamStatus.PUBLISHED.code());
        exam.setStartAt(NOW.minusHours(1));
        exam.setEndAt(NOW.plusHours(2));
        exam.setDurationMinutes(60);
        when(examMapper.selectById(EXAM_ID)).thenReturn(exam);
        when(recordMapper.findByExamAndUser(EXAM_ID, STUDENT)).thenReturn(null);
        PaperItem q1 = item(1L);
        when(paperCache.get(EXAM_ID)).thenReturn(List.of(q1));

        ExamSessionService.StartResult result = service.start(EXAM_ID, STUDENT);

        assertEquals(1, result.paper().size());
    }

    @Test
    void 交过卷之后仍然能取到原题用于渲染成绩单() {
        // 取卷面这件事本身不限制答卷状态：成绩页要把作答和原题拼起来，
        // 没有题干就渲染不出一张能看的成绩单
        ExamRecord done = record(STUDENT);
        done.setStatus(RecordStatus.GRADED.code());
        done.setScore(87);
        when(recordMapper.findByExamAndUser(EXAM_ID, STUDENT)).thenReturn(done);
        when(paperCache.get(EXAM_ID)).thenReturn(List.of(item(1L)));

        assertEquals(1, service.paperForStudent(EXAM_ID, STUDENT).size());
    }
}
