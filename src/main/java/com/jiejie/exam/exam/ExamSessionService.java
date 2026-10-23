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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 学生考试的主流程：进入考试 -> 看卷 -> 保存作答 -> 交卷 -> 看成绩。
 *
 * <p>这个服务里有两个容易被做错的判断，都在代码里显式写了出来：
 * <ol>
 *   <li><b>截止时间在进入时就固定</b>（见 {@link #computeDeadline}），
 *       并额外被考试窗口的 end_at 截断 —— 否则 9:50 进场的 60 分钟考试能考到 10:50，
 *       而 10:00 这场考试就该结束了。</li>
 *   <li><b>重复进入 / 重复交卷都靠数据库裁决</b>，不靠"先查一下有没有"。
 *       唯一键冲突和"影响行数为 0"是唯一的可靠信号。</li>
 * </ol>
 */
@Service
public class ExamSessionService {

    /** 进入考试的结果。serverTime 是给学生端对齐时钟用的 */
    /**
     * 进入考试的结果。serverTime 是给学生端对齐时钟用的。
     *
     * <p><b>status 是这份答卷当前的状态，不只是 "ongoing"。</b>
     * 重复进入时返回的是**原来那份**答卷 —— 如果它已经交了卷，这里拿到的就是
     * submitted/graded。调用方必须按 status 决定是进答题页还是跳成绩页：
     * 只看"拿到卷子了"就渲染答题界面的话，学生会在一个已经交掉的卷子上继续答题，
     * 而每一次保存都会被 saveAnswer 拒掉。卷面照样返回，是因为成绩页也要看原题。
     */
    public record StartResult(Long recordId, Long examId, String status, LocalDateTime deadlineAt,
                              LocalDateTime serverTime, List<PaperItem> paper) {
    }

    /** score 为 null 表示"已交卷，判分中" */
    public record SubmitResult(Long recordId, String status, Integer score) {
    }

    /**
     * 成绩页需要的东西。
     *
     * <p>{@code answers} 只有 questionId / userAnswer / correct / scoreGot —— 没有题干，
     * 所以单靠它渲染不出一张能看的成绩单。{@code paper} 带上原题（不含正确答案，
     * 用的是同一个 PaperItem），前端按 questionId 把两者对起来。
     *
     * <p>{@code examId} 是给前端跳回考试用的，顺带省掉一次查询。
     */
    public record ExamResultView(Long recordId, Long examId, Integer score, String status,
                                 List<PaperItem> paper, List<ExamAnswer> answers) {
    }

    private final ExamMapper examMapper;
    private final ExamRecordMapper recordMapper;
    private final ExamAnswerMapper answerMapper;
    private final ExamQuestionMapper examQuestionMapper;
    private final PaperCache paperCache;
    private final GradingService gradingService;
    private final GradingProducer gradingProducer;
    private final Clock clock;
    private final boolean asyncGrading;

    public ExamSessionService(ExamMapper examMapper,
                              ExamRecordMapper recordMapper,
                              ExamAnswerMapper answerMapper,
                              ExamQuestionMapper examQuestionMapper,
                              PaperCache paperCache,
                              GradingService gradingService,
                              GradingProducer gradingProducer,
                              Clock clock,
                              @Value("${exam.grading.async}") boolean asyncGrading) {
        this.examMapper = examMapper;
        this.recordMapper = recordMapper;
        this.answerMapper = answerMapper;
        this.examQuestionMapper = examQuestionMapper;
        this.paperCache = paperCache;
        this.gradingService = gradingService;
        this.gradingProducer = gradingProducer;
        this.clock = clock;
        this.asyncGrading = asyncGrading;
    }

    // ---------------- 进入考试 ----------------

    /**
     * 进入考试。已经进入过的（刷新页面、换设备）直接返回原答卷，不会重开一份。
     */
    public StartResult start(Long examId, Long userId) {
        Exam exam = examMapper.selectById(examId);
        if (exam == null) {
            throw new BusinessException("考试不存在: " + examId);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (!ExamStatus.PUBLISHED.code().equals(exam.getStatus())) {
            throw new BusinessException("该考试未发布，不能进入");
        }
        if (now.isBefore(exam.getStartAt())) {
            throw new BusinessException("考试尚未开始");
        }
        if (!now.isBefore(exam.getEndAt())) {
            throw new BusinessException("考试已结束");
        }

        ExamRecord existing = recordMapper.findByExamAndUser(examId, userId);
        if (existing != null) {
            return new StartResult(existing.getId(), examId, existing.getStatus(),
                    existing.getDeadlineAt(), now, paper(examId));
        }

        LocalDateTime deadline = computeDeadline(now, exam.getDurationMinutes(), exam.getEndAt());
        ExamRecord record = new ExamRecord();
        record.setExamId(examId);
        record.setUserId(userId);
        record.setStatus(RecordStatus.ONGOING.code());
        record.setStartedAt(now);
        record.setDeadlineAt(deadline);
        try {
            recordMapper.insert(record);
        } catch (DuplicateKeyException e) {
            // 并发进入：另一个请求先插进去了。唯一键替我们做了互斥，
            // 这里只要把已有的那份读出来返回即可。
            ExamRecord again = recordMapper.findByExamAndUser(examId, userId);
            if (again == null) {
                throw e;
            }
            return new StartResult(again.getId(), examId, again.getStatus(),
                    again.getDeadlineAt(), now, paper(examId));
        }
        return new StartResult(record.getId(), examId, RecordStatus.ONGOING.code(),
                deadline, now, paper(examId));
    }

    /**
     * 计算应截止时刻：开始时间 + 时长，但<b>不会超过考试窗口的结束时间</b>。
     *
     * <p>写成静态方法是为了能被单测直接钉住 —— 这段逻辑错了会让学生凭空多出考试时间，
     * 而且只在"考试快结束时才进场"这种边缘情况下才暴露，靠手点很难复现。
     */
    static LocalDateTime computeDeadline(LocalDateTime startedAt, int durationMinutes, LocalDateTime examEndAt) {
        LocalDateTime byDuration = startedAt.plusMinutes(durationMinutes);
        return examEndAt.isBefore(byDuration) ? examEndAt : byDuration;
    }

    // ---------------- 取卷面 ----------------

    public List<PaperItem> paper(Long examId) {
        List<PaperItem> cached = paperCache.get(examId);
        if (cached != null) {
            return cached;
        }
        String token = paperCache.tryLock(examId, 10);
        if (token == null) {
            // 没抢到重建锁：说明别人正在重建。再读一次缓存，
            // 还是没有就直接查库 —— 宁可多一次查询，也不能让学生拿不到卷子。
            List<PaperItem> again = paperCache.get(examId);
            return again != null ? again : examQuestionMapper.selectPaper(examId);
        }
        try {
            // 双重检查：抢到锁之后要再读一次，可能上一个持有者刚写完
            List<PaperItem> again = paperCache.get(examId);
            if (again != null) {
                return again;
            }
            List<PaperItem> paper = examQuestionMapper.selectPaper(examId);
            paperCache.put(examId, paper);
            return paper;
        } finally {
            paperCache.unlock(examId, token);
        }
    }

    // ---------------- 保存作答 ----------------

    /**
     * 保存（覆盖）一道题的作答。学生端每答一题就自动保存一次，所以这里是 upsert。
     */
    public void saveAnswer(Long recordId, Long userId, Long questionId, String answer) {
        ExamRecord record = requireOwnRecord(recordId, userId);
        if (!RecordStatus.ONGOING.code().equals(record.getStatus())) {
            throw new BusinessException("答卷已提交，不能再修改作答");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (!now.isBefore(record.getDeadlineAt())) {
            throw new BusinessException("考试时间已结束，无法保存作答");
        }
        if (!paperQuestionIds(record.getExamId()).contains(questionId)) {
            // 不让客户端往答卷里塞卷子上没有的题 —— 否则总分会被凭空抬高
            throw new BusinessException("该题不在本场试卷中: " + questionId);
        }
        answerMapper.upsert(recordId, questionId, answer == null || answer.isBlank() ? null : answer);
    }

    // ---------------- 交卷 ----------------

    public SubmitResult submit(Long recordId, Long userId) {
        ExamRecord record = requireOwnRecord(recordId, userId);
        RecordStatus status = RecordStatus.of(record.getStatus());

        if (status == RecordStatus.GRADED) {
            return new SubmitResult(recordId, status.code(), record.getScore());
        }
        if (status == RecordStatus.SUBMITTED) {
            // 已经交过卷，判分可能还在队列里。重复点击不该报错，也不该重新判
            return new SubmitResult(recordId, status.code(), null);
        }

        LocalDateTime now = LocalDateTime.now(clock);
        // 用时按"开始 → 应截止"记，和 AutoSubmitJob 一个口径：
        // 学生答完不点交卷、过半小时才回来点，那半小时不是答题时间。
        // 用 min(now, deadline) 而不是直接取 deadline：正常交卷（now < deadline）
        // 记的是真实用时，只有超时才被夹到 deadline。
        LocalDateTime usedUntil = now.isAfter(record.getDeadlineAt()) ? record.getDeadlineAt() : now;
        int seconds = (int) Math.max(0, Duration.between(record.getStartedAt(), usedUntil).toSeconds());
        int rows = recordMapper.markSubmitted(recordId, now, seconds);
        if (rows == 0) {
            // 并发重复交卷：另一个请求已经把状态改走了。以先到者为准
            ExamRecord again = recordMapper.selectById(recordId);
            return new SubmitResult(recordId, again.getStatus(), again.getScore());
        }

        if (asyncGrading && gradingProducer.trySend(recordId)) {
            return new SubmitResult(recordId, RecordStatus.SUBMITTED.code(), null);
        }
        // 没配 MQ、或 Broker 不可用：降级成同步判分，慢但结果一致
        int score = gradingService.grade(recordId);
        return new SubmitResult(recordId, RecordStatus.GRADED.code(), score);
    }

    // ---------------- 查成绩 ----------------

    public ExamResultView result(Long recordId, Long userId) {
        ExamRecord record = requireOwnRecord(recordId, userId);
        if (!RecordStatus.GRADED.code().equals(record.getStatus())) {
            throw new BusinessException("还没判完，请稍后再看");
        }
        return new ExamResultView(recordId, record.getExamId(), record.getScore(), record.getStatus(),
                paper(record.getExamId()), answerMapper.listByRecord(recordId));
    }

    // ---------------- 内部 ----------------

    private ExamRecord requireOwnRecord(Long recordId, Long userId) {
        ExamRecord record = recordMapper.selectById(recordId);
        if (record == null) {
            throw new BusinessException("答卷不存在: " + recordId);
        }
        if (!record.getUserId().equals(userId)) {
            // 不返回 404 而是 403：答卷确实存在，只是不是你的
            throw new BusinessException(403, "无权访问他人答卷");
        }
        return record;
    }

    private Set<Long> paperQuestionIds(Long examId) {
        return paper(examId).stream().map(PaperItem::getId).collect(Collectors.toSet());
    }
}
