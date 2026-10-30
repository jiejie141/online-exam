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
    /** 源系统里的题目 ID（C-Eval 是字符串、MMLU 是整数、Excel 是行号）。手工出的题为 null */
    private String externalId;
    /** manual | ceval | cmmlu | mmlu | excel | school —— 见 QuestionSource */
    private String source;
    /** 出处：数据集版本、文件名 + 行号、或授权编号 */
    private String sourceRef;
    /** 许可证原文；null = 未知，不等于"可以随便用" */
    private String license;
    /**
     * 去重指纹：题型 + 归一化题干 + 归一化选项的 SHA-256。
     *
     * <p>由 {@code ContentHash} 计算，不接受调用方直接传 —— 两个算法不一致
     * 就等于没有指纹（同一道题会算出两个值，去重永远命中不了）。
     * 历史数据为 null，导入服务会在首次导入时回填。
     */
    private String contentHash;
    private Long createdBy;
    private LocalDateTime createdAt;
}
