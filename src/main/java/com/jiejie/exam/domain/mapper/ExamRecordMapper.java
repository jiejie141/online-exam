package com.jiejie.exam.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jiejie.exam.domain.dto.ExamScoreStats;
import com.jiejie.exam.domain.dto.ScoreBucket;
import com.jiejie.exam.domain.entity.ExamRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ExamRecordMapper extends BaseMapper<ExamRecord> {

    /**
     * 一个学生在一场考试里的记录。uk_record_exam_user 保证最多一行。
     * 走 uk_record_exam_user(exam_id, user_id) —— 唯一索引同样能被查询使用。
     */
    @Select("SELECT id, exam_id, user_id, status, score, started_at, deadline_at, submitted_at, duration_seconds " +
            "FROM exam_record WHERE exam_id = #{examId} AND user_id = #{userId}")
    ExamRecord findByExamAndUser(@Param("examId") Long examId, @Param("userId") Long userId);

    /** 老师端看"已交卷 / 未交卷"人数。走 idx_record_exam_status(exam_id, status) */
    @Select("SELECT COUNT(*) FROM exam_record WHERE exam_id = #{examId} AND status = #{status}")
    long countByExamAndStatus(@Param("examId") Long examId, @Param("status") String status);

    /**
     * 成绩概览。及格人数要拿 exam.pass_score 比，所以 JOIN 一次考试表，
     * 而不是把及格线当参数传进来 —— 传参意味着调用方得先查一次考试，且可能传错。
     *
     * <p>score IS NOT NULL 才是"已判分"的判据：status 是 graded 但 score 为 NULL 的行不算，
     * 这样即使判分中途失败也不会把脏数据算进平均分。
     */
    @Select("SELECT COUNT(*) AS total, " +
            "       AVG(r.score) AS avg_score, " +
            "       MAX(r.score) AS max_score, " +
            "       MIN(r.score) AS min_score, " +
            "       SUM(CASE WHEN r.score >= e.pass_score THEN 1 ELSE 0 END) AS pass_count " +
            "FROM exam_record r JOIN exam e ON e.id = r.exam_id " +
            "WHERE r.exam_id = #{examId} AND r.score IS NOT NULL")
    ExamScoreStats stats(@Param("examId") Long examId);

    /**
     * 成绩分布，10 分一档。MySQL 里 / 是浮点除法，所以 FLOOR(85/10)*10 = 80；
     * 用 DIV 才会得到整数除法，这里要的正是向下取整的效果。
     */
    @Select("SELECT FLOOR(score / 10) * 10 AS bucket, COUNT(*) AS cnt " +
            "FROM exam_record WHERE exam_id = #{examId} AND score IS NOT NULL " +
            "GROUP BY FLOOR(score / 10) * 10 ORDER BY bucket")
    List<ScoreBucket> distribution(@Param("examId") Long examId);

    /**
     * 超时未交卷的记录，交给定时任务强制收卷。
     * 走 idx_record_deadline(status, deadline_at)。
     *
     * <p>用 LIMIT：一场大考可能有几千份同时到期，一次全捞出来会让事务拖得很长，
     * 分批捞，下一轮调度接着处理。
     */
    @Select("SELECT id, exam_id, user_id, status, score, started_at, deadline_at, submitted_at, duration_seconds " +
            "FROM exam_record WHERE status = 'ongoing' AND deadline_at <= #{now} " +
            "ORDER BY deadline_at LIMIT #{limit}")
    List<ExamRecord> findExpiredOngoing(@Param("now") LocalDateTime now, @Param("limit") int limit);

    /**
     * 收卷：状态改成 submitted。
     *
     * <p>带上 status = 'ongoing' 这个条件是关键 —— 并发下两个请求同时收卷时，
     * 只有一个能把行从 ongoing 改走，另一个的 affected rows 为 0。
     * 靠数据库的行锁做幂等，不靠应用层"先查再改"。
     */
    @Update("UPDATE exam_record SET status = 'submitted', submitted_at = #{submittedAt}, " +
            "duration_seconds = #{durationSeconds} " +
            "WHERE id = #{id} AND status = 'ongoing'")
    int markSubmitted(@Param("id") Long id,
                      @Param("submittedAt") LocalDateTime submittedAt,
                      @Param("durationSeconds") Integer durationSeconds);

    /** 判分完成后写入总分。同样用 status 条件保证只写一次 */
    @Update("UPDATE exam_record SET score = #{score}, status = 'graded' " +
            "WHERE id = #{id} AND status = 'submitted'")
    int markGraded(@Param("id") Long id, @Param("score") int score);
}
