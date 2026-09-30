package com.jiejie.exam.domain.dto;

import lombok.Data;

/** 成绩分布的一个分数段，如 [80,90) 段有 12 人 */
@Data
public class ScoreBucket {
    /** 段下界，按 10 分一档 */
    private Integer bucket;
    private Integer cnt;
}
