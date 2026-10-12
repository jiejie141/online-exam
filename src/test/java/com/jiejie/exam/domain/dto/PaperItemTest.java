package com.jiejie.exam.domain.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 卷面做题用的 DTO —— 这里只有一条规则，但它是**安全规则**：
 * 发给学生的 JSON 里绝不能出现正确答案。
 *
 * <p>为什么值得写一条测试：这个属性是靠「没写这个字段」实现的，
 * 而不是靠任何显式检查。也就是说，**破坏它不需要删掉任何代码 ——
 * 只需要有人顺手加一个字段**（比如为了调试打印答案，或者从 Question
 * 复制粘贴时多带了一行）。那一刻不会有任何编译错误、也没有别的测试会红，
 * 学生打开开发者工具就能看到答案。
 *
 * <p>所以这里从两个角度各钉一次：
 * 字段列表（编译期结构）和序列化结果（运行期实际发出去的东西）。
 * 后者更重要 —— 即使没有 answer 字段，一个 @JsonProperty("answer") 的
 * 其他字段照样会泄露。
 */
class PaperItemTest {

    /**
     * 必须手动开 NON_NULL 来对齐应用的真实行为。
     *
     * <p>裸 ObjectMapper 不读 application.yml —— 那里配了
     * `default-property-inclusion: non_null`，是 **Spring Boot 的** ObjectMapper
     * 才会应用的。我第一版忘了这件事，于是「判断题不该发 options」这条测试
     * 报出 `"options":null` 而失败 —— 那是测试环境的差异，不是代码的问题。
     *
     * <p>这里的做法是显式模拟应用配置：测的是「实际发出去的 JSON」，
     * 而不是「PaperItem 自己的属性」。两者的区别在下面那条测试的注释里说明了。
     */
    private final ObjectMapper mapper = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    /** 与数据库 question 表里正确答案那列的列名一致，见 schema.sql */
    private static final List<String> 泄露相关的字段名 = List.of("answer", "correctAnswer");

    @Test
    void 类上没有叫answer的字段() {
        for (String name : 泄露相关的字段名) {
            boolean exists = Arrays.stream(PaperItem.class.getDeclaredFields())
                    .map(Field::getName)
                    .anyMatch(f -> f.equalsIgnoreCase(name));
            assertFalse(exists,
                    "PaperItem 上出现了 " + name + " 字段 —— 它会随 JSON 一起发给学生，"
                            + "等于把答案印在卷子上。要给学生看的字段请回到这个类的注释里确认。");
        }
    }

    @Test
    void 序列化后的JSON里没有任何答案字段() throws Exception {
        PaperItem item = new PaperItem();
        item.setId(8L);
        item.setType("multiple");
        item.setStem("关于数据库索引，下列说法正确的有？");
        item.setOptions("[\"A\",\"B\",\"C\",\"D\"]");
        item.setScore(10);
        item.setSeq(3);

        String json = mapper.writeValueAsString(item);

        for (String name : 泄露相关的字段名) {
            assertFalse(json.toLowerCase().contains(name.toLowerCase()),
                    "序列化结果里出现了 " + name + "：" + json);
        }
    }

    @Test
    void 该有的字段一个不少() throws Exception {
        // 反向断言：上面那条是「不能有哪些」，这条保证「该有的还有」。
        // 少了它，一个 return null 的实现也能让上面两条通过。
        PaperItem item = new PaperItem();
        item.setId(8L);
        item.setType("multiple");
        item.setStem("题干");
        item.setOptions("[\"A\"]");
        item.setScore(10);
        item.setSeq(3);

        String json = mapper.writeValueAsString(item);

        for (String field : List.of("id", "type", "stem", "options", "score", "seq")) {
            assertTrue(json.contains(field), "卷面缺少 " + field + " 字段：" + json);
        }
    }

    @Test
    void 判断题的options可以为null_序列化时被剔除() throws Exception {
        // 判断题没有选项。PaperItem 的注释写明「判断题为 null」。
        // 而 application.yml 配了 NON_NULL，所以这个字段应该整个不出现，
        // 而不是发一个 "options": null 给前端。
        PaperItem item = new PaperItem();
        item.setId(1L);
        item.setType("judge");
        item.setStem("这道题对还是错");
        item.setScore(5);
        item.setSeq(1);

        String json = mapper.writeValueAsString(item);

        assertFalse(json.contains("options"), "判断题不应发出 options 字段：" + json);
        assertTrue(json.contains("judge"));
    }

    @Test
    void 默认构造出的对象各字段都是null_不是0() {
        // score/seq 是 Integer 而不是 int，这一点有实际意义：
        // 用 int 的话，一个漏填的题目会显示成 0 分，看起来像「这道题不计分」；
        // Integer 的 null 才能表达「没设过」。
        PaperItem item = new PaperItem();

        assertEquals(null, item.getScore());
        assertEquals(null, item.getSeq());
        assertEquals(null, item.getId());
    }
}
