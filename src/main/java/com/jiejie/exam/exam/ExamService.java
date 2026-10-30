package com.jiejie.exam.exam;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jiejie.exam.cache.PaperCache;
import com.jiejie.exam.domain.dto.PageResult;
import com.jiejie.exam.domain.entity.Exam;
import com.jiejie.exam.domain.entity.ExamQuestion;
import com.jiejie.exam.domain.entity.Question;
import com.jiejie.exam.domain.mapper.ExamMapper;
import com.jiejie.exam.domain.mapper.ExamQuestionMapper;
import com.jiejie.exam.domain.mapper.QuestionMapper;
import com.jiejie.exam.web.BusinessException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 老师端：建考试、组卷、发布、结束。
 *
 * <p>所有「按 examId 操作」的方法都多收一个 {@code teacherId}，并用
 * {@link ExamAccess#requireOwned} 判归属。这不是可选的装饰：
 * 老师端的入口只校验了 ROLE_teacher，也就是「你是个老师」，
 * 而没校验「这场考试是你的」——少了这一层，任何老师都能发布、
 * 结束甚至改别人的卷子，还能看到别人那场考试的成绩统计。
 */
@Service
public class ExamService {

    private final ExamMapper examMapper;
    private final ExamQuestionMapper examQuestionMapper;
    private final QuestionMapper questionMapper;
    private final PaperCache paperCache;
    private final ExamAccess examAccess;

    public ExamService(ExamMapper examMapper,
                       ExamQuestionMapper examQuestionMapper,
                       QuestionMapper questionMapper,
                       PaperCache paperCache,
                       ExamAccess examAccess) {
        this.examMapper = examMapper;
        this.examQuestionMapper = examQuestionMapper;
        this.questionMapper = questionMapper;
        this.paperCache = paperCache;
        this.examAccess = examAccess;
    }

    public Long createExam(String title, String subject, LocalDateTime startAt, LocalDateTime endAt,
                           int durationMinutes, int passScore, Long createdBy) {
        if (!endAt.isAfter(startAt)) {
            throw new BusinessException("结束时间必须晚于开始时间");
        }
        if (durationMinutes <= 0) {
            throw new BusinessException("考试时长必须大于 0");
        }
        // 及格线不能是负数。发布时只判「及格线高于卷面总分」，
        // 负及格线永远小于总分，反而一路畅通 —— 建出来的考试等于全员及格。
        if (passScore < 0) {
            throw new BusinessException("及格线不能是负数");
        }
        Exam exam = new Exam();
        exam.setTitle(title);
        exam.setSubject(subject);
        exam.setStartAt(startAt);
        exam.setEndAt(endAt);
        exam.setDurationMinutes(durationMinutes);
        exam.setPassScore(passScore);
        exam.setStatus(ExamStatus.DRAFT.code());
        exam.setCreatedBy(createdBy);
        examMapper.insert(exam);
        return exam.getId();
    }

    /**
     * 往卷子里加题。分值在这里指定，同一道题在不同考试里可以不同权重。
     *
     * <p>uk_exam_question(exam_id, question_id) 会挡住重复加题；
     * 这里捕获 DuplicateKeyException 转成「跳过」而不是报错 ——
     * 老师重复点了两次提交，不该丢一个 500 出来。
     */
    public int addQuestions(Long examId, List<Long> questionIds, Integer score, Long teacherId) {
        Exam exam = examAccess.requireOwned(examId, teacherId);
        if (!ExamStatus.DRAFT.code().equals(exam.getStatus())) {
            throw new BusinessException("只有草稿状态的考试才能改卷子");
        }
        // 组卷时给的分值同样必须为正。这里不判的话，老师可以把 100 分的题
        // 用 score=-7 覆盖成负分，后面 publish 的总分也跟着算成负数，
        // 而学生交卷时 GradingRule 直接抛异常 —— 整场考试永远判不出来。
        if (score != null && score <= 0) {
            throw new BusinessException("卷面分值必须大于 0");
        }
        // 先全部校验、再全部写入。这个方法没有 @Transactional，
        // 边校验边写的话，列表中间那道"别人的题"会把前几道已经写进库的题留下来，
        // 卷子变成改了一半的半成品，而调用方只看到一个 403。
        List<Question> questions = new ArrayList<>();
        for (Long questionId : questionIds) {
            Question q = questionMapper.selectById(questionId);
            if (q == null) {
                throw new BusinessException("题目不存在: " + questionId);
            }
            // 题目也有归属。只判「考试是你的」不够：题库里别人的题可以被
            // 悄悄塞进自己的卷子，学生考完看到的是一道你没出过、也看不到答案的题。
            // 同样 fail-closed：created_by 为空的题目不让进卷子。
            if (!teacherId.equals(q.getCreatedBy())) {
                throw new BusinessException(403, "不能把别人的题目加进自己的卷子: " + questionId);
            }
            // 不指定分值就沿用题库里的分值；题库里的分值也可能是历史脏数据，
            // 所以取出来的实际值仍要判一次正数。
            int effective = score != null ? score : (q.getScore() == null ? 0 : q.getScore());
            if (effective <= 0) {
                throw new BusinessException("题目 " + questionId + " 的分值不合法: " + effective);
            }
            // 题库里可能躺着修复之前建出来的脏题（答案不在选项里、判断题答案写成中文）。
            // 组卷是它进入试卷的最后一道关口，这里再验一次 ——
            // 否则一条脏题就能让整场考试所有人都拿不到那道题的分。
            QuestionRule.validate(parseType(q.getType()), q.getOptions(), q.getAnswer());
            questions.add(q);
        }
        int added = 0;
        int seq = currentMaxSeq(examId);
        for (Question q : questions) {
            ExamQuestion eq = new ExamQuestion();
            eq.setExamId(examId);
            eq.setQuestionId(q.getId());
            eq.setScore(score != null ? score : q.getScore());
            eq.setSeq(++seq);
            try {
                examQuestionMapper.insert(eq);
                added++;
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // 已经加过了，跳过
            }
        }
        return added;
    }

    /** 发布。发布后卷子就固定了，所以这里主动清一次缓存，避免学生拉到旧卷 */
    public void publish(Long examId, Long teacherId) {
        Exam exam = examAccess.requireOwned(examId, teacherId);
        if (!ExamStatus.DRAFT.code().equals(exam.getStatus())) {
            throw new BusinessException("只有草稿状态才能发布");
        }
        List<ExamQuestion> items = examQuestionMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ExamQuestion>()
                        .eq(ExamQuestion::getExamId, examId));
        if (items.isEmpty()) {
            throw new BusinessException("卷子还没有题目，不能发布");
        }
        // 总分由卷面实际分值累加得出，而不是老师手填 ——
        // 手填的总分和卷面加不起来是对不上的，考试里这种错很致命
        int totalScore = items.stream().mapToInt(i -> i.getScore() == null ? 0 : i.getScore()).sum();
        if (exam.getPassScore() != null && exam.getPassScore() > totalScore) {
            throw new BusinessException("及格线 " + exam.getPassScore() + " 高于卷面总分 " + totalScore);
        }
        exam.setTotalScore(totalScore);
        exam.setStatus(ExamStatus.PUBLISHED.code());
        examMapper.updateById(exam);
        paperCache.evict(examId);
    }

    public void close(Long examId, Long teacherId) {
        Exam exam = examAccess.requireOwned(examId, teacherId);
        ExamStatus current = ExamStatus.of(exam.getStatus());
        if (!current.canTransferTo(ExamStatus.CLOSED)) {
            throw new BusinessException("当前状态不能结束: " + current.code());
        }
        exam.setStatus(ExamStatus.CLOSED.code());
        examMapper.updateById(exam);
    }

    /**
     * 老师端考试列表 —— 只返回自己建的。
     *
     * <p>按开始时间倒序：最近要开的排在最前面，这才是老师打开页面时想先看到的东西。
     *
     * <p>原来没有 created_by 条件，任何老师都能在列表里看到别人的考试，
     * 点进去还能继续操作 —— 归属校验形同虚设。
     */
    public PageResult<Exam> listExams(Long teacherId, long page, long size) {
        Page<Exam> p = new Page<>(page, size);
        IPage<Exam> result = examMapper.selectPage(p,
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Exam>()
                        .eq(Exam::getCreatedBy, teacherId)
                        .orderByDesc(Exam::getStartAt));
        return new PageResult<>(result.getRecords(), result.getTotal(), result.getCurrent(), result.getSize());
    }

    /**
     * 题库列表。subject / type 都可为空，为空就不加这个条件。
     *
     * <p>这里返回的是 {@link Question}（<b>含正确答案</b>），只给老师端用。
     * 学生端任何路径都必须走 {@code PaperItem}，那是没有 answer 字段的 DTO。
     *
     * <p>同样按 created_by 过滤：题库也是老师的私有资源，
     * 不加这一条，A 老师能看到 B 老师出的题和答案。
     */
    public PageResult<Question> listQuestions(String subject, String type, Long teacherId,
                                             long page, long size) {
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Question> q =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
        q.eq(Question::getCreatedBy, teacherId);
        if (subject != null && !subject.isBlank()) {
            q.eq(Question::getSubject, subject);
        }
        if (type != null && !type.isBlank()) {
            q.eq(Question::getType, type);
        }
        q.orderByDesc(Question::getId);
        IPage<Question> result = questionMapper.selectPage(new Page<>(page, size), q);
        return new PageResult<>(result.getRecords(), result.getTotal(), result.getCurrent(), result.getSize());
    }

    public Long createQuestion(Question question) {
        // 答案必须先校验，否则题目进了库才发现判不了分。
        //
        // QuestionType.of 抛的是 IllegalArgumentException，直接漏到
        // GlobalExceptionHandler 的兜底分支会变成 500「服务内部错误」。
        // 可这明明是调用方传错了 type，重试一万次也不会成功 ——
        // 转成业务异常，让 4xx 归 4xx。
        QuestionType type;
        try {
            type = QuestionType.of(question.getType());
        } catch (IllegalArgumentException e) {
            throw new BusinessException("题型只能是 single / multiple / judge: " + question.getType());
        }
        // 分值必须是正数。负数分值的题能建出来，但它永远判不了分：
        // GradingRule 第一行就抛 "fullScore must not be negative"，
        // 消费端只能记一条 error 然后放弃，答卷永远停在「还没判完」。
        if (question.getScore() == null || question.getScore() <= 0) {
            throw new BusinessException("题目分值必须大于 0");
        }
        if (question.getDifficulty() != null && question.getDifficulty() < 1) {
            throw new BusinessException("题目难度必须大于等于 1");
        }
        // 归属必须落库：题目的可读性完全靠 created_by 过滤，
        // 存成 NULL 的题目从此谁都看不见，等于写进了一个黑洞
        if (question.getCreatedBy() == null) {
            throw new BusinessException("题目必须记录出题老师");
        }
        // 答案与选项必须自洽。这一步挡的不是"参数格式"，是"这道题根本没法判对"：
        // 实测过判断题答案写成「正确」、单选题答案是 Z 而选项只有 A–D ——
        // 两种都能一路入库、发布、让学生作答，然后全班 0 分，且全程不报错。
        QuestionRule.validate(type, question.getOptions(), question.getAnswer());
        questionMapper.insert(question);
        return question.getId();
    }

    /** 题库里的题型可能是历史脏数据，解析失败要回 400 而不是让 IllegalArgumentException 变成 500 */
    private static QuestionType parseType(String code) {
        try {
            return QuestionType.of(code);
        } catch (IllegalArgumentException e) {
            throw new BusinessException("题库里的题型不合法: " + code);
        }
    }

    private int currentMaxSeq(Long examId) {
        List<ExamQuestion> list = examQuestionMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ExamQuestion>()
                        .eq(ExamQuestion::getExamId, examId));
        return list.stream().mapToInt(eq -> eq.getSeq() == null ? 0 : eq.getSeq()).max().orElse(0);
    }
}
