package com.jiejie.exam.exam;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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
        // 归属必须落库：题目的可读性完全靠 created_by 过滤，
        // 存成 NULL 的题目从此谁都看不见，等于写进了一个黑洞
        if (question.getCreatedBy() == null) {
            throw new BusinessException("题目必须记录出题老师");
        }
        validateQuestionContent(question);
        questionMapper.insert(question);
        return question.getId();
    }

    /**
     * 题目内容校验：题型、分值、难度、答案与选项的自洽性。
     *
     * <p>建题和改题共用同一份。分开写两份的后果很具体：改题那条路会漏掉某一条
     * 校验，于是"建的时候进不去的坏题"能从"改"这条路进去 —— 而坏题不会报错，
     * 只会让全班都拿不到那道题的分。
     *
     * <p>返回解析好的题型：调用方偶尔还要用它（例如日志里记一笔）。
     */
    private static QuestionType validateQuestionContent(Question question) {
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
        // 上限也是 3。库注释写的是「1 易 2 中 3 难」，但这里原来只判了下界 ——
        // 于是页面上的难度输入框能填到 5（前端 max=5），建出来的题难度是 4/5。
        // 后果不是报错，而是<b>这类题按难度永远抽不到</b>：自动组卷只按 1/2/3 筛，
        // 4/5 的题只有在「不限难度」时才可能出现，而且看不出哪里不对。
        if (question.getDifficulty() != null && question.getDifficulty() > 3) {
            throw new BusinessException("题目难度只能是 1 / 2 / 3");
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
        return type;
    }


    // ==================================================================
    // 回头路：改题 / 删题 / 从草稿试卷里移除
    // ==================================================================
    // 在这三个方法之前，这个老师端只有"往前"的动作（建题 → 组卷 → 发布 → 结束）。
    // 一旦发现题出错了，唯一的路是重建一整场考试 —— 这不是"少个功能"，
    // 是让前面所有并发与一致性设计都失去意义：一套不能纠错的系统，
    // 用得越认真，代价越大。

    /**
     * 改题。
     *
     * <p><b>为什么"被已发布考试引用"就整题冻结</b>：答案是判分依据。
     * 一场已经发布（甚至已经考完判过分）的考试里，把某道题的答案从 B 改成 C，
     * 那些按 B 判过的卷子就同时存在两种解释：卷面显示的是新答案，
     * 分数是按旧答案算的。库里没有任何字段记录"这条答卷是按哪一版答案判的"，
     * 所以这种不一致一旦产生，永远查不出来。
     *
     * <p>规则刻意定得比"只冻结答案和选项"更宽 —— 整题冻结。理由是两个：
     * 分支少一半（不用去比对 options 的规范化形式），说法只有一句
     * （"已发布的考试里题目不可改"）。代价是连改错别字也被挡住，
     * 但这比留一个半开的口子好：半开的口子讲不清，也测不全。
     *
     * <p>草稿考试里被引用不受影响 —— 那场考试还没人考，改完重新发布即可。
     */
    public void updateQuestion(Long questionId, Question patch, Long teacherId) {
        Question existing = requireOwnQuestion(questionId, teacherId);

        List<ExamQuestion> refs = examQuestionMapper.selectList(
                new LambdaQueryWrapper<ExamQuestion>().eq(ExamQuestion::getQuestionId, questionId));
        if (!refs.isEmpty()) {
            Set<Long> examIds = refs.stream().map(ExamQuestion::getExamId)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            List<Exam> locked = examMapper.selectBatchIds(examIds).stream()
                    .filter(e -> !ExamStatus.DRAFT.code().equals(e.getStatus()))
                    .toList();
            if (!locked.isEmpty()) {
                String where = locked.stream()
                        .map(e -> "「" + e.getTitle() + "」")
                        .collect(Collectors.joining("、"));
                throw new BusinessException("这道题在已发布的考试 " + where
                        + " 里，题目已冻结。已发布的试卷改答案，会让「卷面显示新答案、"
                        + "分数按旧答案算」的矛盾永久留在数据里。要更正请先结束那场考试并新建。");
            }
        }

        existing.setSubject(patch.getSubject());
        existing.setType(patch.getType());
        existing.setStem(patch.getStem());
        existing.setOptions(patch.getOptions());
        existing.setAnswer(patch.getAnswer());
        existing.setScore(patch.getScore());
        existing.setDifficulty(patch.getDifficulty());
        validateQuestionContent(existing);
        questionMapper.updateById(existing);
    }

    /**
     * 删题。
     *
     * <p>被任何试卷引用就拒绝 —— 删掉之后那几份卷子会少一道题，
     * 而历史答卷里的作答记录仍然指着这个 question_id。学生查成绩时会看到
     * "一道空白题拿了 5 分"这种东西，比报错难查得多。
     *
     * <p>没有做软删除（status 字段）：题库里"不用的题"最常见的原因是录错了，
     * 而录错的题留着只会让下次组卷再抽到它一次。真要保留历史，正确做法是
     * 先从试卷里移除（草稿可直接移除，已发布的考试本来就冻结着），
     * 而不是给题目加一个"删了但还在"的状态 —— 那种状态每个查询都要记得过滤，
     * 漏一处就会在最不该出现的地方冒出来。
     */
    public void deleteQuestion(Long questionId, Long teacherId) {
        requireOwnQuestion(questionId, teacherId);

        List<ExamQuestion> refs = examQuestionMapper.selectList(
                new LambdaQueryWrapper<ExamQuestion>().eq(ExamQuestion::getQuestionId, questionId));
        if (!refs.isEmpty()) {
            String where = refs.stream().map(r -> "#" + r.getExamId())
                    .distinct().limit(3).collect(Collectors.joining("、"));
            throw new BusinessException("这道题还在 " + refs.size() + " 份试卷里（考试 " + where
                    + "），不能删。删掉会让那些试卷少一道题，历史答卷的作答也会指向"
                    + "一个不存在的题目。先从草稿试卷里移除它。");
        }
        questionMapper.deleteById(questionId);
    }

    /**
     * 从草稿试卷里移除一道题。
     *
     * <p>为什么只允许草稿：已发布的卷子是学生正在作答的那一份，中途抽掉一道题，
     * 已经答过它的学生提交时会被判成"答了卷面上没有的题"。草稿没有这个约束 ——
     * 那正是组卷阶段必须能反复修改的理由。
     *
     * <p>移除后不重排 seq：seq 只用于卷面排序，留空号不影响取题顺序
     * （{@code ORDER BY seq} 照样对）。重排要改动其他行，为了"刚删掉一道题"
     * 去写一条批量 UPDATE 不划算，而多出来的更新会进 binlog、要进事务。
     */
    public void removeQuestion(Long examId, Long questionId, Long teacherId) {
        Exam exam = examAccess.requireOwned(examId, teacherId);
        if (!ExamStatus.DRAFT.code().equals(exam.getStatus())) {
            throw new BusinessException("只有草稿状态的考试才能改卷子");
        }
        int deleted = examQuestionMapper.delete(new LambdaQueryWrapper<ExamQuestion>()
                .eq(ExamQuestion::getExamId, examId)
                .eq(ExamQuestion::getQuestionId, questionId));
        if (deleted == 0) {
            throw new BusinessException("这道题不在本场试卷里: " + questionId);
        }
    }

    /** 取一道题并确认是当前老师出的。改和删走同一道闸门，别各写一遍。 */
    private Question requireOwnQuestion(Long questionId, Long teacherId) {
        Question q = questionMapper.selectById(questionId);
        if (q == null) {
            throw new BusinessException("题目不存在: " + questionId);
        }
        // created_by 为 NULL 一律拒绝（fail-closed）：这种题谁都改得了，
        // 也谁都看不见，属于历史脏数据，正确做法是清理而不是放行
        if (q.getCreatedBy() == null || !q.getCreatedBy().equals(teacherId)) {
            throw new BusinessException(403, "只能修改自己出的题目: " + questionId);
        }
        return q;
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
