package com.jiejie.exam.exam;

import com.jiejie.exam.cache.PaperCache;
import com.jiejie.exam.domain.dto.PaperItem;
import com.jiejie.exam.domain.entity.Exam;
import com.jiejie.exam.domain.entity.ExamAnswer;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExamSessionServiceTest {

    /** 固定时间：2026-10-01 09:00:00 */
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

    private static Exam exam(String status, LocalDateTime startAt, LocalDateTime endAt, int duration) {
        Exam e = new Exam();
        e.setId(1L);
        e.setStatus(status);
        e.setStartAt(startAt);
        e.setEndAt(endAt);
        e.setDurationMinutes(duration);
        return e;
    }

    // ---------------- 截止时间计算 ----------------

    @Test
    void 正常进场按考试时长算截止时间() {
        assertEquals(LocalDateTime.of(2026, 10, 1, 10, 0),
                ExamSessionService.computeDeadline(NOW, 60, LocalDateTime.of(2026, 10, 1, 10, 30)));
    }

    @Test
    void 快结束时进场会被考试窗口截断不会多出时间() {
        // 9:50 进场、时长 60 分钟，但考试 10:00 就结束了 —— 只能考到 10:00
        LocalDateTime entered = LocalDateTime.of(2026, 10, 1, 9, 50);
        assertEquals(LocalDateTime.of(2026, 10, 1, 10, 0),
                ExamSessionService.computeDeadline(entered, 60, LocalDateTime.of(2026, 10, 1, 10, 0)));
    }

    @Test
    void 时长短于窗口时按时长算() {
        assertEquals(LocalDateTime.of(2026, 10, 1, 9, 30),
                ExamSessionService.computeDeadline(NOW, 30, LocalDateTime.of(2026, 10, 1, 10, 0)));
    }

    // ---------------- 进入考试 ----------------

    @Test
    void 未发布的考试不能进入() {
        when(examMapper.selectById(1L)).thenReturn(exam(ExamStatus.DRAFT.code(),
                NOW.minusHours(1), NOW.plusHours(2), 60));

        assertThrows(BusinessException.class, () -> service.start(1L, 7L));
    }

    @Test
    void 时间窗还没开不能进入() {
        when(examMapper.selectById(1L)).thenReturn(exam(ExamStatus.PUBLISHED.code(),
                NOW.plusMinutes(10), NOW.plusHours(2), 60));

        assertThrows(BusinessException.class, () -> service.start(1L, 7L));
    }

    @Test
    void 已经进入过就返回原答卷而不是重开一份() {
        when(examMapper.selectById(1L)).thenReturn(exam(ExamStatus.PUBLISHED.code(),
                NOW.minusHours(1), NOW.plusHours(2), 60));
        ExamRecord existing = new ExamRecord();
        existing.setId(500L);
        existing.setStatus(RecordStatus.ONGOING.code());
        existing.setDeadlineAt(LocalDateTime.of(2026, 10, 1, 9, 45));
        when(recordMapper.findByExamAndUser(1L, 7L)).thenReturn(existing);
        when(paperCache.get(1L)).thenReturn(List.of());

        ExamSessionService.StartResult result = service.start(1L, 7L);

        assertEquals(500L, result.recordId());
        // 关键：没有新插入答卷
        // 这里必须写 any(ExamRecord.class)：BaseMapper 有 insert(T) 和 insert(Collection<T>)
        // 两个重载，裸写 any() 编译期就无法确定用哪个
        org.mockito.Mockito.verify(recordMapper, org.mockito.Mockito.never())
                .insert(org.mockito.ArgumentMatchers.any(ExamRecord.class));
    }

    @Test
    void 重新进场要把已经存过的作答带回来() {
        // 刷新页面 / 换设备重进只走 start 这一条路径。
        // 如果这里不带回作答，前端只能把每道题当成空白：学生看到一张全白的卷子，
        // 以为答案丢了（其实库里都在），答题卡「已答 0/10」和交卷弹窗里
        // 「还有 10 道题没有作答」也全是假的。
        when(examMapper.selectById(1L)).thenReturn(exam(ExamStatus.PUBLISHED.code(),
                NOW.minusHours(1), NOW.plusHours(2), 60));
        ExamRecord existing = new ExamRecord();
        existing.setId(500L);
        existing.setStatus(RecordStatus.ONGOING.code());
        existing.setDeadlineAt(LocalDateTime.of(2026, 10, 1, 9, 45));
        when(recordMapper.findByExamAndUser(1L, 7L)).thenReturn(existing);
        when(paperCache.get(1L)).thenReturn(List.of());

        ExamAnswer saved = new ExamAnswer();
        saved.setRecordId(500L);
        saved.setQuestionId(11L);
        saved.setUserAnswer("A");
        when(answerMapper.listByRecord(500L)).thenReturn(List.of(saved));

        ExamSessionService.StartResult result = service.start(1L, 7L);

        assertEquals(1, result.answers().size());
        assertEquals(11L, result.answers().get(0).getQuestionId());
        assertEquals("A", result.answers().get(0).getUserAnswer());
    }

    @Test
    void 首次进场不去查作答也不必查() {
        // 刚插进去的答卷不可能有作答，多查一次库纯属浪费。
        // 这条断言钉住的是「别顺手加一次无谓的查询」。
        when(examMapper.selectById(1L)).thenReturn(exam(ExamStatus.PUBLISHED.code(),
                NOW.minusHours(1), NOW.plusHours(2), 60));
        when(recordMapper.findByExamAndUser(1L, 7L)).thenReturn(null);
        when(paperCache.get(1L)).thenReturn(List.of());

        ExamSessionService.StartResult result = service.start(1L, 7L);

        assertTrue(result.answers().isEmpty());
        verify(answerMapper, never()).listByRecord(anyLong());
    }

    @Test
    void 首次进场写入的截止时间是算出来的那个值() {
        when(examMapper.selectById(1L)).thenReturn(exam(ExamStatus.PUBLISHED.code(),
                NOW.minusHours(1), NOW.plusHours(2), 60));
        when(recordMapper.findByExamAndUser(1L, 7L)).thenReturn(null);
        when(paperCache.get(1L)).thenReturn(List.of());

        service.start(1L, 7L);

        ArgumentCaptor<ExamRecord> captor = ArgumentCaptor.forClass(ExamRecord.class);
        verify(recordMapper).insert(captor.capture());
        assertEquals(LocalDateTime.of(2026, 10, 1, 10, 0), captor.getValue().getDeadlineAt());
        assertEquals(RecordStatus.ONGOING.code(), captor.getValue().getStatus());
    }

    @Test
    void 并发进场撞唯一键时读回已有答卷而不是报错() {
        when(examMapper.selectById(1L)).thenReturn(exam(ExamStatus.PUBLISHED.code(),
                NOW.minusHours(1), NOW.plusHours(2), 60));
        when(recordMapper.findByExamAndUser(1L, 7L)).thenReturn(null);
        when(paperCache.get(1L)).thenReturn(List.of());
        org.mockito.Mockito.doThrow(new DuplicateKeyException("uk_record_exam_user"))
                .when(recordMapper).insert(org.mockito.ArgumentMatchers.any(ExamRecord.class));

        ExamRecord winner = new ExamRecord();
        winner.setId(777L);
        winner.setDeadlineAt(LocalDateTime.of(2026, 10, 1, 10, 0));
        when(recordMapper.findByExamAndUser(1L, 7L)).thenReturn(null, winner);

        ExamSessionService.StartResult result = service.start(1L, 7L);

        assertEquals(777L, result.recordId());
    }

    // ---------------- 保存作答 ----------------

    @Test
    void 超过截止时间不能再保存作答() {
        ExamRecord record = new ExamRecord();
        record.setId(500L);
        record.setExamId(1L);
        record.setUserId(7L);
        record.setStatus(RecordStatus.ONGOING.code());
        record.setStartedAt(NOW.minusMinutes(70));
        record.setDeadlineAt(NOW.minusMinutes(10));
        when(recordMapper.selectById(500L)).thenReturn(record);

        assertThrows(BusinessException.class, () -> service.saveAnswer(500L, 7L, 1L, "A"));
    }

    @Test
    void 不能保存卷子上没有的题() {
        ExamRecord record = new ExamRecord();
        record.setId(500L);
        record.setExamId(1L);
        record.setUserId(7L);
        record.setStatus(RecordStatus.ONGOING.code());
        record.setStartedAt(NOW);
        record.setDeadlineAt(NOW.plusMinutes(60));
        when(recordMapper.selectById(500L)).thenReturn(record);

        PaperItem item = new PaperItem();
        item.setId(1L);
        when(paperCache.get(1L)).thenReturn(List.of(item));

        assertThrows(BusinessException.class, () -> service.saveAnswer(500L, 7L, 999L, "A"));
        verify(answerMapper, org.mockito.Mockito.never())
                .upsert(anyLong(), anyLong(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void 空白作答按未作答存储而不是空字符串() {
        ExamRecord record = new ExamRecord();
        record.setId(500L);
        record.setExamId(1L);
        record.setUserId(7L);
        record.setStatus(RecordStatus.ONGOING.code());
        record.setStartedAt(NOW);
        record.setDeadlineAt(NOW.plusMinutes(60));
        when(recordMapper.selectById(500L)).thenReturn(record);

        PaperItem item = new PaperItem();
        item.setId(1L);
        when(paperCache.get(1L)).thenReturn(List.of(item));

        service.saveAnswer(500L, 7L, 1L, "   ");

        verify(answerMapper).upsert(500L, 1L, null);
    }

    // ---------------- 交卷 ----------------

    @Test
    void 重复交卷第二次直接返回不重新判分() {
        ExamRecord record = new ExamRecord();
        record.setId(500L);
        record.setUserId(7L);
        record.setExamId(1L);
        record.setStatus(RecordStatus.SUBMITTED.code());
        record.setStartedAt(NOW);
        when(recordMapper.selectById(500L)).thenReturn(record);

        ExamSessionService.SubmitResult result = service.submit(500L, 7L);

        assertEquals(RecordStatus.SUBMITTED.code(), result.status());
        assertNull(result.score());
        verify(recordMapper, org.mockito.Mockito.never())
                .markSubmitted(anyLong(), org.mockito.ArgumentMatchers.any(), anyInt());
    }

    @Test
    void 已判分的答卷重复交卷返回原分数() {
        ExamRecord record = new ExamRecord();
        record.setId(500L);
        record.setUserId(7L);
        record.setExamId(1L);
        record.setStatus(RecordStatus.GRADED.code());
        record.setScore(82);
        record.setStartedAt(NOW);
        when(recordMapper.selectById(500L)).thenReturn(record);

        ExamSessionService.SubmitResult result = service.submit(500L, 7L);

        assertEquals(82, result.score());
    }

    @Test
    void 首次交卷改成submitted并投递判分消息() {
        ExamRecord record = new ExamRecord();
        record.setId(500L);
        record.setUserId(7L);
        record.setExamId(1L);
        record.setStatus(RecordStatus.ONGOING.code());
        record.setStartedAt(NOW.minusMinutes(20));
        record.setDeadlineAt(NOW.plusMinutes(40));
        when(recordMapper.selectById(500L)).thenReturn(record);
        when(recordMapper.markSubmitted(eq(500L), eq(NOW), eq(1200))).thenReturn(1);
        when(gradingProducer.trySend(500L)).thenReturn(true);

        ExamSessionService.SubmitResult result = service.submit(500L, 7L);

        assertEquals(RecordStatus.SUBMITTED.code(), result.status());
        assertNull(result.score());
        verify(gradingProducer).trySend(500L);
    }

    @Test
    void 已交卷的答卷重新进场要带上状态让前端能跳成绩页() {
        // 重复进入返回的是原来那份答卷；如果它已经交了卷，
        // status 必须是 submitted —— 调用方据此跳成绩页而不是渲染答题界面。
        // 只看"拿到卷子了"就进答题页的话，学生每答一题都会被 saveAnswer 拒掉。
        Exam exam = new Exam();
        exam.setId(1L);
        exam.setStatus(ExamStatus.PUBLISHED.code());
        exam.setStartAt(NOW.minusHours(1));
        exam.setEndAt(NOW.plusHours(1));
        exam.setDurationMinutes(60);
        when(examMapper.selectById(1L)).thenReturn(exam);

        ExamRecord done = new ExamRecord();
        done.setId(500L);
        done.setExamId(1L);
        done.setUserId(7L);
        done.setStatus(RecordStatus.SUBMITTED.code());
        done.setDeadlineAt(NOW.plusMinutes(30));
        when(recordMapper.findByExamAndUser(1L, 7L)).thenReturn(done);

        ExamSessionService.StartResult result = service.start(1L, 7L);

        assertEquals(500L, result.recordId());
        assertEquals(RecordStatus.SUBMITTED.code(), result.status());
    }

    @Test
    void 首次进场的状态是ongoing() {
        Exam exam = new Exam();
        exam.setId(1L);
        exam.setStatus(ExamStatus.PUBLISHED.code());
        exam.setStartAt(NOW.minusHours(1));
        exam.setEndAt(NOW.plusHours(1));
        exam.setDurationMinutes(60);
        when(examMapper.selectById(1L)).thenReturn(exam);
        when(recordMapper.findByExamAndUser(1L, 7L)).thenReturn(null);

        ExamSessionService.StartResult result = service.start(1L, 7L);

        assertEquals(RecordStatus.ONGOING.code(), result.status());
    }

    @Test
    void 正常交卷的用时按真实时间记() {
        // 20 分钟前进场，60 分钟的考试，现在交：还没到截止，用时就是 20 分钟
        ExamRecord record = new ExamRecord();
        record.setId(500L);
        record.setUserId(7L);
        record.setExamId(1L);
        record.setStatus(RecordStatus.ONGOING.code());
        record.setStartedAt(NOW.minusMinutes(20));
        record.setDeadlineAt(NOW.plusMinutes(40));
        when(recordMapper.selectById(500L)).thenReturn(record);
        when(recordMapper.markSubmitted(eq(500L), eq(NOW), eq(1200))).thenReturn(1);
        when(gradingProducer.trySend(500L)).thenReturn(true);

        service.submit(500L, 7L);

        // submitted_at 仍然是真实的交卷时刻
        verify(recordMapper).markSubmitted(eq(500L), eq(NOW), eq(1200));
    }

    @Test
    void 超时很久才点交卷的用时被夹到截止时间() {
        // README 的口径：用时按"开始 -> 应截止"记。
        // 学生 20 分钟前进场、10 分钟前就已截止，现在才点交卷：
        // 真实用时是 20 分钟，但超过截止的那 10 分钟不是答题时间。
        ExamRecord record = new ExamRecord();
        record.setId(500L);
        record.setUserId(7L);
        record.setExamId(1L);
        record.setStatus(RecordStatus.ONGOING.code());
        record.setStartedAt(NOW.minusMinutes(20));
        record.setDeadlineAt(NOW.minusMinutes(10));
        when(recordMapper.selectById(500L)).thenReturn(record);
        when(recordMapper.markSubmitted(eq(500L), eq(NOW), eq(600))).thenReturn(1);
        when(gradingProducer.trySend(500L)).thenReturn(true);

        service.submit(500L, 7L);

        // 记 600 秒（开始 -> 截止），不是 1200 秒（开始 -> 现在）；
        // 但 submitted_at 仍是真实的 NOW，两者语义不同不能混
        verify(recordMapper).markSubmitted(eq(500L), eq(NOW), eq(600));
    }

    @Test
    void 消息投递失败时降级成同步判分不能把卷子挂在未判分() {
        ExamRecord record = new ExamRecord();
        record.setId(500L);
        record.setUserId(7L);
        record.setExamId(1L);
        record.setStatus(RecordStatus.ONGOING.code());
        record.setStartedAt(NOW.minusMinutes(20));
        record.setDeadlineAt(NOW.plusMinutes(40));
        when(recordMapper.selectById(500L)).thenReturn(record);
        when(recordMapper.markSubmitted(eq(500L), eq(NOW), eq(1200))).thenReturn(1);
        when(gradingProducer.trySend(500L)).thenReturn(false);
        when(gradingService.grade(500L)).thenReturn(73);

        ExamSessionService.SubmitResult result = service.submit(500L, 7L);

        assertEquals(RecordStatus.GRADED.code(), result.status());
        assertEquals(73, result.score());
    }

    @Test
    void 并发交卷时markSubmitted返回0就读取已有状态() {
        ExamRecord record = new ExamRecord();
        record.setId(500L);
        record.setUserId(7L);
        record.setExamId(1L);
        record.setStatus(RecordStatus.ONGOING.code());
        record.setStartedAt(NOW.minusMinutes(20));
        record.setDeadlineAt(NOW.plusMinutes(40));
        when(recordMapper.selectById(500L)).thenReturn(record);
        when(recordMapper.markSubmitted(eq(500L), eq(NOW), eq(1200))).thenReturn(0);

        ExamRecord afterRace = new ExamRecord();
        afterRace.setId(500L);
        afterRace.setStatus(RecordStatus.SUBMITTED.code());
        when(recordMapper.selectById(500L)).thenReturn(record, afterRace);

        ExamSessionService.SubmitResult result = service.submit(500L, 7L);

        assertEquals(RecordStatus.SUBMITTED.code(), result.status());
        verify(gradingProducer, org.mockito.Mockito.never()).trySend(anyLong());
    }

    @Test
    void 不能访问别人的答卷() {
        ExamRecord record = new ExamRecord();
        record.setId(500L);
        record.setUserId(999L);
        when(recordMapper.selectById(500L)).thenReturn(record);

        BusinessException e = assertThrows(BusinessException.class, () -> service.submit(500L, 7L));
        assertEquals(403, e.code());
    }

    @Test
    void 取卷面缓存没命中时查库并回填() {
        when(paperCache.get(1L)).thenReturn(null);
        when(paperCache.tryLock(1L, 10)).thenReturn("token");
        PaperItem item = new PaperItem();
        item.setId(1L);
        when(examQuestionMapper.selectPaper(1L)).thenReturn(List.of(item));

        List<PaperItem> paper = service.loadPaper(1L);

        assertEquals(1, paper.size());
        verify(paperCache).put(1L, List.of(item));
        verify(paperCache).unlock(1L, "token");
    }

    @Test
    void 没抢到锁时直接查库不阻塞学生拿卷子() {
        when(paperCache.get(1L)).thenReturn(null);
        when(paperCache.tryLock(1L, 10)).thenReturn(null);
        when(examQuestionMapper.selectPaper(1L)).thenReturn(List.of());

        assertEquals(List.of(), service.loadPaper(1L));
    }

    @Test
    void 查成绩时还没判完要报错而不是返回0分() {
        ExamRecord record = new ExamRecord();
        record.setId(500L);
        record.setUserId(7L);
        record.setStatus(RecordStatus.SUBMITTED.code());
        when(recordMapper.selectById(500L)).thenReturn(record);

        assertThrows(BusinessException.class, () -> service.result(500L, 7L));
    }
}
