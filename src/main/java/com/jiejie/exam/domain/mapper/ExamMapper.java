package com.jiejie.exam.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jiejie.exam.domain.entity.Exam;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ExamMapper extends BaseMapper<Exam> {

    /**
     * 到点关考：找出所有"已发布但时间窗已过"的考试。
     * 走 idx_exam_status_end(status, end_at)。
     */
    @Select("SELECT id, title, subject, start_at, end_at, duration_minutes, total_score, pass_score, " +
            "       status, created_by, created_at " +
            "FROM exam WHERE status = 'published' AND end_at <= #{now} ORDER BY end_at")
    List<Exam> findPublishedEndedBefore(@Param("now") LocalDateTime now);

    /** 学生端"可参加的考试"：窗口已开且还没结束 */
    @Select("SELECT id, title, subject, start_at, end_at, duration_minutes, total_score, pass_score, " +
            "       status, created_by, created_at " +
            "FROM exam WHERE status = 'published' AND start_at <= #{now} AND end_at > #{now} " +
            "ORDER BY start_at")
    List<Exam> findOngoingExams(@Param("now") LocalDateTime now);
}
