package com.jiejie.exam.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 试卷组成。分值存在这里而不是题目表：同一道题在不同考试里可以有不同权重。 */
@Data
@TableName("exam_question")
public class ExamQuestion {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long examId;
    private Long questionId;
    private Integer score;
    private Integer seq;
}
