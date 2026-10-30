package com.jiejie.exam.exam;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jiejie.exam.domain.entity.Exam;
import com.jiejie.exam.domain.entity.Question;
import com.jiejie.exam.domain.mapper.QuestionMapper;
import com.jiejie.exam.web.BusinessException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * 按配额自动组卷：告诉它「单选 10 道、多选 5 道、判断 5 道」，它从题库里抽。
 *
 * <p><b>为什么要这个功能</b>：手工组卷一次要在一页 20 条的题库列表里翻几十页，
 * 而且每翻一页都要记住自己选过哪几道。题量到几百道以后，
 * 老师实际的做法是"就用手上这几道"—— 题库建了等于没建。
 *
 * <p><b>为什么是"配额"而不是"随机抽 20 道"</b>：考试的题型比例和难度分布
 * 是有教学意义的（单选考记忆、多选考辨析、难题拉区分度）。
 * 完全随机抽出来的卷子，每次难度都不一样，两次考试的成绩没法比较。
 *
 * <p><b>三个刻意的取舍</b>：
 * <ol>
 *   <li><b>只取 id 再内存洗牌，不用 {@code ORDER BY RAND()}</b>。
 *       {@code ORDER BY RAND()} 会给整张表算一次随机数再排序，走不了索引，
 *       而且每次都要把全部行物化一遍。这里只 select id（几千题的 id 列表
 *       是几十 KB），在内存里洗牌取前 N —— 代价可控且与题型筛选条件无关。</li>
 *   <li><b>数量不足就报错，不降级</b>。抽到 7 道也发布出去，卷面总分就和
 *       老师的预期不符；而总分是 {@code publish} 时算出来的，
 *       错误会一路传到成绩统计里。宁可当场拦住。</li>
 *   <li><b>不自己写校验，最后交给 {@link ExamService#addQuestions}</b>。
 *       归属校验、"只有草稿能改卷"、分值正数、题目自洽（{@code QuestionRule}）
 *       都在那边。抽题逻辑如果自己再写一遍，就一定会有一天两份实现不一样。</li>
 * </ol>
 */
@Service
public class AutoComposeService {

    /** 一张卷子的题量上限。不是技术限制，是"手滑填 10000"的护栏 */
    static final int MAX_TOTAL = 200;

    /**
     * 一条配额。
     *
     * @param type       必填：single / multiple / judge
     * @param difficulty 可选：1/2/3；null = 不限难度
     * @param count      本题型抽几道
     */
    public record Quota(String type, Integer difficulty, int count) {
    }

    /** 实际抽到的条数（按配额逐条回报，前端要能显示"单选 10/10、判断 3/5"） */
    public record Picked(String type, Integer difficulty, int count) {
    }

    public record Result(String subject, List<Picked> picked, int added) {
    }

    private final QuestionMapper questionMapper;
    private final ExamAccess examAccess;
    private final ExamService examService;

    public AutoComposeService(QuestionMapper questionMapper, ExamAccess examAccess,
                              ExamService examService) {
        this.questionMapper = questionMapper;
        this.examAccess = examAccess;
        this.examService = examService;
    }

    /**
     * 抽题并写入卷子。
     *
     * @param subject 为 null 时用这场考试的科目 —— 大多数情况下组卷就是按考试科目筛，
     *                让调用方每次都重复传一遍没有意义
     * @param score   每道题的分值；null = 用题库里各题自带的分值
     * @return 实际抽到的明细与写入条数
     */
    public Result compose(Long examId, String subject, List<Quota> quotas, Integer score,
                          Long teacherId) {
        Exam exam = examAccess.requireOwned(examId, teacherId);

        // 这里先判一次状态，而不是等 addQuestions 去判：
        // 否则会先把几千条 id 查出来、洗好牌，最后才告诉老师"这场考试已经发布了"。
        // 白干活本身没事，问题在于一次几十毫秒的查询被放大成十几次。
        if (!ExamStatus.DRAFT.code().equals(exam.getStatus())) {
            throw new BusinessException("只有草稿状态的考试才能自动组卷");
        }
        if (quotas == null || quotas.isEmpty()) {
            throw new BusinessException("至少要给一条配额，如 [{\"type\":\"single\",\"count\":10}]");
        }

        String effectiveSubject = (subject == null || subject.isBlank()) ? exam.getSubject() : subject.trim();

        int totalRequested = 0;
        for (Quota q : quotas) {
            if (q == null || q.type() == null || q.type().isBlank()) {
                throw new BusinessException("配额里的 type 不能为空（single / multiple / judge）");
            }
            if (q.count() <= 0) {
                throw new BusinessException("配额里的 count 必须大于 0: " + q.type() + " -> " + q.count());
            }
            if (q.difficulty() != null && (q.difficulty() < 1 || q.difficulty() > 3)) {
                throw new BusinessException("难度只能是 1/2/3，实际: " + q.difficulty());
            }
            totalRequested += q.count();
        }
        if (totalRequested > MAX_TOTAL) {
            throw new BusinessException("一次最多组 " + MAX_TOTAL + " 道题，本次要 " + totalRequested + " 道");
        }

        // 用 LinkedHashSet 而不是 ArrayList：同一个题型可能在两条配额里各出现一次
        // （比如"单选简单 5 道"+"单选不限难度 5 道"），它们抽到的题不能重复。
        // 抽过的 id 全部记下来，后面的配额从池子里剔掉。
        Set<Long> picked = new LinkedHashSet<>();
        List<Picked> report = new ArrayList<>();

        for (Quota quota : quotas) {
            QuestionType type;
            try {
                type = QuestionType.of(quota.type().trim());
            } catch (IllegalArgumentException e) {
                throw new BusinessException("题型只能是 single / multiple / judge，实际: " + quota.type());
            }

            List<Long> pool = loadPool(effectiveSubject, type, quota.difficulty(), teacherId);
            pool.removeAll(picked);

            if (pool.size() < quota.count()) {
                // 这句话必须能直接指导下一步：是题不够（补题）、还是难度筛太窄（放宽）、
                // 还是科目写错了（改科目）。所以三样都报出来。
                throw new BusinessException("题库里「" + effectiveSubject + " / " + type.code()
                        + (quota.difficulty() == null ? " / 不限难度" : " / 难度 " + quota.difficulty())
                        + "」只有 " + pool.size() + " 道可用，本次要抽 " + quota.count()
                        + " 道。请减少数量、放宽难度，或先补题。");
            }

            Collections.shuffle(pool, new Random());
            List<Long> take = pool.subList(0, quota.count());
            picked.addAll(take);
            report.add(new Picked(type.code(), quota.difficulty(), take.size()));
        }

        // 校验与写库都交给 addQuestions：它是"题目进入试卷的最后一道关口"，
        // 会再验一次归属、分值和自洽性（QuestionRule）。
        int added = examService.addQuestions(examId, new ArrayList<>(picked), score, teacherId);
        return new Result(effectiveSubject, report, added);
    }

    /**
     * 取候选 id 列表。
     *
     * <p>只 select id：抽题不需要题干，而题干 + 选项是整个 question 表最宽的两列。
     * 把 6000 道题的完整内容拉进内存只是为了洗牌，纯属浪费。
     *
     * <p>索引走 {@code idx_question_owner_pick(created_by, subject, type, difficulty)}，
     * 三列等值 + created_by 在最左，正好是最理想的形态。
     */
    private List<Long> loadPool(String subject, QuestionType type, Integer difficulty, long teacherId) {
        List<Object> raw = questionMapper.selectObjs(
                new LambdaQueryWrapper<Question>()
                        .select(Question::getId)
                        .eq(Question::getCreatedBy, teacherId)
                        .eq(Question::getSubject, subject)
                        .eq(Question::getType, type.code())
                        .eq(difficulty != null, Question::getDifficulty, difficulty));

        List<Long> ids = new ArrayList<>(raw.size());
        for (Object o : raw) {
            if (o instanceof Number n) {
                ids.add(n.longValue());
            }
        }
        return ids;
    }
}
