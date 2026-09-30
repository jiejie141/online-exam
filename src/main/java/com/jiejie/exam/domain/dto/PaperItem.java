package com.jiejie.exam.domain.dto;

import lombok.Data;

/**
 * 卷面上的一道题 —— 给学生的版本。
 *
 * <p><b>这是一个刻意不包含 answer 字段的 DTO。</b>
 * 如果直接把 Question 实体返回给前端，正确答案会随 JSON 一起送到浏览器，
 * 学生打开开发者工具就看到了。用 DTO 做物理隔离，比在 Controller 里"记得删掉 answer"可靠。
 */
@Data
public class PaperItem {
    private Long id;
    private String type;
    private String stem;
    /** JSON 数组字符串；判断题为 null */
    private String options;
    /** 本题在本卷的分值 */
    private Integer score;
    private Integer seq;
}
