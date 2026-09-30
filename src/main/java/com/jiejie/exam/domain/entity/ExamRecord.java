package com.jiejie.exam.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一场考试里一个学生的一条记录。
 *
 * <p>deadline_at 在进入考试时就固定下来，而不是每次现算（startedAt + duration）——
 * 否则老师中途改了考试时长，正在考的人会凭空多出或少掉时间。
 */
@Data
@TableName("exam_record")
public class ExamRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long examId;
    private Long userId;
    /** ongoing | submitted | graded */
    private String status;
    /** 判分后写入。未判分是 NULL 而不是 0 —— 0 分和没判分是两件事 */
    private Integer score;
    private LocalDateTime startedAt;
    private LocalDateTime deadlineAt;
    private LocalDateTime submittedAt;
    private Integer durationSeconds;
}
