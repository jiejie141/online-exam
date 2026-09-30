package com.jiejie.exam.domain.dto;

import lombok.Data;

/** 某道题的作答情况，用于讲评时找出"全班都错"的题 */
@Data
public class QuestionAccuracy {
    private Long questionId;
    private String stem;
    private Integer attempt;
    private Integer correctCount;
}
