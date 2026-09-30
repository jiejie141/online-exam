package com.jiejie.exam.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 考试。
 *
 * <p>start_at / end_at 是"允许进入的时间窗"，duration_minutes 是"进去之后能考多久"。
 * 两者是独立的：窗口内任意时刻进入都能考满时长，窗口一关就不许再进。
 */
@Data
@TableName("exam")
public class Exam {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String title;
    private String subject;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    private Integer durationMinutes;
    private Integer totalScore;
    private Integer passScore;
    /** draft | published | closed */
    private String status;
    private Long createdBy;
    private LocalDateTime createdAt;
}
