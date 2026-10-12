package com.jiejie.exam.grading;

import com.jiejie.exam.domain.entity.ExamAnswer;
import com.jiejie.exam.domain.entity.ExamRecord;
import com.jiejie.exam.domain.mapper.ExamAnswerMapper;
import com.jiejie.exam.domain.mapper.ExamQuestionMapper;
import com.jiejie.exam.domain.mapper.ExamRecordMapper;
import com.jiejie.exam.exam.QuestionType;
import com.jiejie.exam.exam.RecordStatus;
import com.jiejie.exam.web.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 判分。
 *
 * <p>入口是"一份答卷"，产出是总分。逐题判分规则本身在 {@link GradingRule} 里（纯函数、已单测），
 * 这里只负责取数、循环、写回，不掺任何判断逻辑。
 *
 * <p>判分是 <b>幂等</b> 的：重复调用不会把分数加两遍。
 * 靠的是 {@code markGraded} 里 {@code WHERE status='submitted'} 这个条件 ——
 * 第二次调用时状态已经是 graded，影响行数为 0，抛异常让本次事务回滚。
 */
@Service
public class GradingService {

    /** 卷面上某道题的判分依据 */
    public record AnswerKey(QuestionType type, String answer, int score) {
    }

    private final ExamRecordMapper recordMapper;
    private final ExamAnswerMapper answerMapper;
    private final ExamQuestionMapper examQuestionMapper;

    public GradingService(ExamRecordMapper recordMapper,
                          ExamAnswerMapper answerMapper,
                          ExamQuestionMapper examQuestionMapper) {
        this.recordMapper = recordMapper;
        this.answerMapper = answerMapper;
        this.examQuestionMapper = examQuestionMapper;
    }

    @Transactional
    public int grade(Long recordId) {
        ExamRecord record = recordMapper.selectById(recordId);
        if (record == null) {
            throw new BusinessException("答卷不存在: " + recordId);
        }
        RecordStatus status = RecordStatus.of(record.getStatus());
        if (status == RecordStatus.GRADED) {
            // 已经判过了：直接返回已有分数，不再算一遍
            return record.getScore() == null ? 0 : record.getScore();
        }
        if (status != RecordStatus.SUBMITTED) {
            throw new BusinessException("答卷尚未交卷，不能判分");
        }

        Map<Long, AnswerKey> keyMap = loadAnswerKey(record.getExamId());
        List<ExamAnswer> answers = answerMapper.listByRecord(recordId);

        int total = 0;
        for (ExamAnswer answer : answers) {
            AnswerKey key = keyMap.get(answer.getQuestionId());
            if (key == null) {
                // 题目已被老师从卷子里移除，但学生之前保存的作答还在。
                // 这种历史行不参与计分，也不删 —— 删了就查不到学生当时答了什么。
                continue;
            }
            GradingRule.Result r = GradingRule.grade(key.type(), key.answer(), answer.getUserAnswer(), key.score());
            answerMapper.updateGrade(answer.getId(), r.correct(), r.scoreGot());
            total += r.scoreGot();
        }
        // 卷子里有、但 exam_answer 里没有的题 = 学生没答，按 0 分计，不需要补一行

        int rows = recordMapper.markGraded(recordId, total);
        if (rows == 0) {
            // 并发下另一个消费者先判完了。抛异常让本次事务整体回滚，
            // 避免 exam_answer 被写两遍。以先到者为准。
            //
            // 用 AlreadyGradedException 而不是 BusinessException：这是**正常**的重复，
            // 不是故障。消费者对两者的处理相反（见该异常的类注释）。
            throw new AlreadyGradedException(recordId);
        }
        return total;
    }

    /**
     * 读出本场考试的"标准答案 + 每题分值"。
     *
     * <p>这里必须注意：MyBatis <b>不会</b>对 Map 类型的结果做下划线转驼峰，
     * 所以 SQL 里别名写成 {@code question_id} 就必须用 {@code question_id} 取，
     * 写成 {@code questionId} 会取到 null —— 这种错误不会报错，只会让总分变成 0。
     */
    private Map<Long, AnswerKey> loadAnswerKey(Long examId) {
        Map<Long, AnswerKey> map = new HashMap<>();
        for (Map<String, Object> row : examQuestionMapper.selectAnswerKey(examId)) {
            Long questionId = ((Number) row.get("question_id")).longValue();
            QuestionType type = QuestionType.of((String) row.get("type"));
            String answer = (String) row.get("answer");
            int score = ((Number) row.get("score")).intValue();
            map.put(questionId, new AnswerKey(type, answer, score));
        }
        return map;
    }
}
