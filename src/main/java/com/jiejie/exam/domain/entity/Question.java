package com.jiejie.exam.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 题目。options 存 JSON 字符串（如 [{"key":"A","text":"..."}]）。
 *
 * <p>为什么选项不拆成单独一张表：选项永远跟着题目整体读写，
 * 从来不会单独查询"所有题目的第 3 个选项"。拆表只会让每次取题多一次 JOIN。
 */
@Data
@TableName("question")
public class Question {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String subject;
    /** single | multiple | judge */
    private String type;
    private String stem;
    /** JSON 数组字符串；判断题为 null */
    private String options;
    private String answer;
    private Integer score;
    private Integer difficulty;
    private Long createdBy;
    private LocalDateTime createdAt;
}
