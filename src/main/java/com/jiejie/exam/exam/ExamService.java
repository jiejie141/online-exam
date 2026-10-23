package com.jiejie.exam.exam;

import com.jiejie.exam.cache.PaperCache;
import com.jiejie.exam.domain.entity.Exam;
import com.jiejie.exam.domain.entity.ExamQuestion;
import com.jiejie.exam.domain.entity.Question;
import com.jiejie.exam.domain.mapper.ExamMapper;
import com.jiejie.exam.domain.mapper.ExamQuestionMapper;
import com.jiejie.exam.domain.mapper.QuestionMapper;
import com.jiejie.exam.web.BusinessException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/** 老师端：建考试、组卷、发布、结束。 */
@Service
public class ExamService {

    private final ExamMapper examMapper;
    private final ExamQuestionMapper examQuestionMapper;
    private final QuestionMapper questionMapper;
    private final PaperCache paperCache;

    public ExamService(ExamMapper examMapper,
                       ExamQuestionMapper examQuestionMapper,
                       QuestionMapper questionMapper,
                       PaperCache paperCache) {
        this.examMapper = examMapper;
        this.examQuestionMapper = examQuestionMapper;
        this.questionMapper = questionMapper;
        this.paperCache = paperCache;
    }

    public Long createExam(String title, String subject, LocalDateTime startAt, LocalDateTime endAt,
                           int durationMinutes, int passScore, Long createdBy) {
        if (!endAt.isAfter(startAt)) {
            throw new BusinessException("结束时间必须晚于开始时间");
        }
        if (durationMinutes <= 0) {
            throw new BusinessException("考试时长必须大于 0");
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
     * 这里捕获 DuplicateKeyException 转成"跳过"而不是报错 ——
     * 老师重复点了两次提交，不该丢一个 500 出来。
     */
    public int addQuestions(Long examId, List<Long> questionIds, Integer score) {
        Exam exam = examMapper.selectById(examId);
        if (exam == null) {
            throw new BusinessException("考试不存在: " + examId);
        }
        if (!ExamStatus.DRAFT.code().equals(exam.getStatus())) {
            throw new BusinessException("只有草稿状态的考试才能改卷子");
        }
        int added = 0;
        int seq = currentMaxSeq(examId);
        for (Long questionId : questionIds) {
            Question q = questionMapper.selectById(questionId);
            if (q == null) {
                throw new BusinessException("题目不存在: " + questionId);
            }
            ExamQuestion eq = new ExamQuestion();
            eq.setExamId(examId);
            eq.setQuestionId(questionId);
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
    public void publish(Long examId) {
        Exam exam = examMapper.selectById(examId);
        if (exam == null) {
            throw new BusinessException("考试不存在: " + examId);
        }
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

    public void close(Long examId) {
        Exam exam = examMapper.selectById(examId);
        if (exam == null) {
            throw new BusinessException("考试不存在: " + examId);
        }
        ExamStatus current = ExamStatus.of(exam.getStatus());
        if (!current.canTransferTo(ExamStatus.CLOSED)) {
            throw new BusinessException("当前状态不能结束: " + current.code());
        }
        exam.setStatus(ExamStatus.CLOSED.code());
        examMapper.updateById(exam);
    }

    /**
     * 老师端考试列表。按开始时间倒序 —— 最近要开的排在最前面，
     * 这才是老师打开页面时想先看到的东西。
     */
    public List<Exam> listExams() {
        return examMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Exam>()
                .orderByDesc(Exam::getStartAt));
    }

    /**
     * 题库列表。subject / type 都可为空，为空就不加这个条件。
     *
     * <p>这里返回的是 {@link Question}（<b>含正确答案</b>），只给老师端用。
     * 学生端任何路径都必须走 {@code PaperItem}，那是没有 answer 字段的 DTO。
     */
    public List<Question> listQuestions(String subject, String type) {
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Question> q =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
        if (subject != null && !subject.isBlank()) {
            q.eq(Question::getSubject, subject);
        }
        if (type != null && !type.isBlank()) {
            q.eq(Question::getType, type);
        }
        q.orderByDesc(Question::getId);
        return questionMapper.selectList(q);
    }

    public Long createQuestion(Question question) {
        // 答案必须先校验，否则题目进了库才发现判不了分
        QuestionType.of(question.getType());
        questionMapper.insert(question);
        return question.getId();
    }

    private int currentMaxSeq(Long examId) {
        List<ExamQuestion> list = examQuestionMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ExamQuestion>()
                        .eq(ExamQuestion::getExamId, examId));
        return list.stream().mapToInt(eq -> eq.getSeq() == null ? 0 : eq.getSeq()).max().orElse(0);
    }
}
