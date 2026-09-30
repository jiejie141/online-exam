package com.jiejie.exam.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("exam_answer")
public class ExamAnswer {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long recordId;
    private Long questionId;
    /** 未作答为 NULL，与"答错"要区分开 */
    private String userAnswer;
    private Boolean correct;
    private Integer scoreGot;
    private LocalDateTime answeredAt;
}
