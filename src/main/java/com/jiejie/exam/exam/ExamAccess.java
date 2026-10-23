package com.jiejie.exam.exam;

import com.jiejie.exam.domain.entity.Exam;
import com.jiejie.exam.domain.mapper.ExamMapper;
import com.jiejie.exam.web.BusinessException;
import org.springframework.stereotype.Component;

/**
 * 老师端的归属判定：这场考试是不是当前这位老师的。
 *
 * <p>为什么单独抽成一个类，而不是在每个方法里各写一遍：
 * 老师端有 publish / close / addQuestions / stats / distribution / accuracy / progress
 * 七个入口都要判同一件事。写在各自方法里，等于给后来加端点的人留了一个
 * 「忘了写」的机会 —— 而忘了写不会报错，只会静默地放行。集中到一处之后，
 * 新端点只要走 requireOwned 就自动带上这个约束，忘了走也很显眼
 * （因为它拿不到 Exam 对象）。
 *
 * <p>为什么是 403 而不是 404：这场考试确实存在，只是不属于你。404 会让
 * 「考试不存在」和「不是你的考试」变成同一种回答，反而更容易被用来试探
 * 哪些 id 是真的。学生端的 {@code ExamSessionService.requireOwnRecord}
 * 用的是同一个口径，两边保持一致。
 *
 * <p>created_by 为 NULL 时<b>拒绝</b>（fail-closed），不是放行。这份库里的考试
 * 全部由 {@code createExam} 创建，而它一定会写 created_by，所以这条分支
 * 实际上只会被手写 SQL 造出来的无主数据命中。真出现了，正确做法是回填
 * created_by，而不是让所有老师都能改一场谁都不负责的考试。
 */
@Component
public class ExamAccess {

    private final ExamMapper examMapper;

    public ExamAccess(ExamMapper examMapper) {
        this.examMapper = examMapper;
    }

    /**
     * 取一场考试，并确认它属于 {@code teacherId}。
     *
     * @return 这场考试（调用方通常还要接着看它的状态，省一次查询）
     * @throws BusinessException 不存在（400）或不属于当前老师（403）
     */
    public Exam requireOwned(Long examId, Long teacherId) {
        Exam exam = examMapper.selectById(examId);
        if (exam == null) {
            // 400 而不是 404：和项目里其它「查不到」的地方口径一致
            throw new BusinessException("考试不存在: " + examId);
        }
        if (exam.getCreatedBy() == null) {
            throw new BusinessException(403, "这场考试没有记录归属老师，无法操作: " + examId);
        }
        if (!exam.getCreatedBy().equals(teacherId)) {
            throw new BusinessException(403, "无权操作他人的考试: " + examId);
        }
        return exam;
    }
}
