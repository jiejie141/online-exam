package com.jiejie.exam.domain.dto;

import lombok.Data;

/** 一场考试的成绩概览。字段名靠下划线转驼峰自动映射。 */
@Data
public class ExamScoreStats {
    /** 已判分人数；没人参加时是 0 */
    private Integer total;
    private Double avgScore;
    private Integer maxScore;
    private Integer minScore;
    private Integer passCount;
}
