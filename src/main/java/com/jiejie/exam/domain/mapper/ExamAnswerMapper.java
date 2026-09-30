package com.jiejie.exam.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jiejie.exam.domain.dto.QuestionAccuracy;
import com.jiejie.exam.domain.entity.ExamAnswer;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ExamAnswerMapper extends BaseMapper<ExamAnswer> {

    /**
     * 保存 / 覆盖一次作答。
     *
     * <p>学生端每隔几秒自动保存一次，同一道题会被写很多遍，所以这里是 upsert 而不是 insert。
     * 幂等交给 uk_answer_record_question(record_id, question_id) 裁决：
     * 如果改成"先查有没有，有就 update 没有就 insert"，并发下会插入重复行。
     *
     * <p>MySQL 8.0.19 起 VALUES(col) 写法被标记废弃，改用行别名 AS new。
     */
    @Insert("INSERT INTO exam_answer (record_id, question_id, user_answer, answered_at) " +
            "VALUES (#{recordId}, #{questionId}, #{userAnswer}, NOW(3)) AS new " +
            "ON DUPLICATE KEY UPDATE user_answer = new.user_answer, answered_at = new.answered_at")
    int upsert(@Param("recordId") Long recordId,
               @Param("questionId") Long questionId,
               @Param("userAnswer") String userAnswer);

    /** 判分时一次取出整份答卷。走 idx_answer_record(record_id) */
    @Select("SELECT id, record_id, question_id, user_answer, correct, score_got, answered_at " +
            "FROM exam_answer WHERE record_id = #{recordId}")
    List<ExamAnswer> listByRecord(@Param("recordId") Long recordId);

    /** 写回单题判分结果 */
    @Update("UPDATE exam_answer SET correct = #{correct}, score_got = #{scoreGot} WHERE id = #{id}")
    int updateGrade(@Param("id") Long id,
                    @Param("correct") Boolean correct,
                    @Param("scoreGot") int scoreGot);

    /**
     * 逐题正确率，按正确率升序 —— 排在最前面的就是"全班都错"的题，讲评时先看它。
     *
     * <p>correct 对未作答是 NULL，SUM 遇到全 NULL 会返回 NULL 而不是 0，
     * 所以必须 COALESCE，否则前端拿到 null 会显示成空白。
     *
     * <p>r.score IS NOT NULL 限定只统计已判分的答卷。
     */
    @Select("SELECT q.id AS question_id, q.stem AS stem, " +
            "       COUNT(*) AS attempt_count, " +
            "       COALESCE(SUM(CASE WHEN a.correct = 1 THEN 1 ELSE 0 END), 0) AS correct_count " +
            "FROM exam_answer a " +
            "JOIN question q ON q.id = a.question_id " +
            "JOIN exam_record r ON r.id = a.record_id " +
            "WHERE r.exam_id = #{examId} AND r.score IS NOT NULL " +
            "GROUP BY q.id, q.stem " +
            "ORDER BY COALESCE(SUM(CASE WHEN a.correct = 1 THEN 1 ELSE 0 END), 0) * 1.0 / COUNT(*) ASC, " +
            "         attempt_count DESC")
    List<QuestionAccuracy> accuracyByExam(@Param("examId") Long examId);
}
