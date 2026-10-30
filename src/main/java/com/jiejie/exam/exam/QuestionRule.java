package com.jiejie.exam.exam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jiejie.exam.grading.GradingRule;
import com.jiejie.exam.web.BusinessException;

import java.util.ArrayList;
import java.util.List;

/**
 * 题目的「到底能不能判分」校验。
 *
 * <p>为什么单独抽一个类：判分逻辑在 {@link GradingRule}，但一道题能不能被判分
 * 取决于它<b>入库时</b>长什么样。这两件事必须用同一套归一化规则，
 * 否则会出现「入库时通过了、判分时对不上」的题 —— 而且这种题不会报错，
 * 只会让所有学生都答错，考完才发现某一题全班 0 分。
 *
 * <p>所以这里刻意复用 {@link GradingRule#normalize(String)}，
 * 而不是自己再写一份「去空格、转大写」。
 *
 * <p>拦的都是实测能一路写进库的形态：
 * <ul>
 *   <li>判断题答案写成「正确」「A」「t」—— 判分时归一化后是 "正确"/"A"/"T"，
 *       而学生端只会提交 T/F，除了 "T" 以外永远对不上。</li>
 *   <li>单选题答案是 "Z"，选项却只有 A–D —— 学生怎么选都是错。</li>
 *   <li>选择题没有选项、选项不是合法 JSON、选项 key 重复。</li>
 * </ul>
 */
public final class QuestionRule {

    private static final ObjectMapper JSON = new ObjectMapper();

    private QuestionRule() {
    }

    /** 校验一道题「答案 + 选项」自洽，不自洽就抛 400。 */
    public static void validate(QuestionType type, String options, String answer) {
        switch (type) {
            case JUDGE -> validateJudge(options, answer);
            case SINGLE, MULTIPLE -> validateChoice(type, optionKeys(options), answer);
        }
    }

    private static void validateJudge(String options, String answer) {
        // 判断题在库里 options 必须是 NULL：前端会给它补上 T/F 两个选项，
        // 存一份进库只会让 PaperItem 里多一个没人用的字段，还可能和后补的对不上。
        if (options != null && !options.isBlank()) {
            throw new BusinessException("判断题不该有选项：答案只有 T / F 两个");
        }
        String normalized = GradingRule.normalize(answer);
        if (!"T".equals(normalized) && !"F".equals(normalized)) {
            throw new BusinessException("判断题的答案只能是 T 或 F: " + answer);
        }
    }

    private static void validateChoice(QuestionType type, List<String> keys, String answer) {
        String normalized = GradingRule.normalize(answer);
        if (normalized.isEmpty()) {
            throw new BusinessException("选择题的答案不能为空");
        }
        if (type == QuestionType.SINGLE && normalized.length() != 1) {
            throw new BusinessException("单选题只能有一个答案: " + answer);
        }
        List<String> outside = new ArrayList<>();
        for (char c : normalized.toCharArray()) {
            if (!keys.contains(String.valueOf(c))) {
                outside.add(String.valueOf(c));
            }
        }
        if (!outside.isEmpty()) {
            // 这句话要能直接告诉出题人错在哪：不是「参数不合法」，
            // 而是「这个答案学生永远选不到」。
            throw new BusinessException("答案 " + outside + " 不在选项里，学生永远选不到它。现有选项: " + keys);
        }
    }

    /** 解析选项，返回大写后的 key 列表。解析不了就是 400，不能让数据库去兜底。 */
    static List<String> optionKeys(String options) {
        if (options == null || options.isBlank()) {
            throw new BusinessException("选择题必须有选项，形如 [{\"key\":\"A\",\"text\":\"...\"}]");
        }
        JsonNode root;
        try {
            root = JSON.readTree(options);
        } catch (Exception e) {
            // 不把 options 原样拼进错误消息：它可能很长，而且回显用户输入
            // 没有任何诊断价值 —— 出题人自己知道刚才填了什么。
            throw new BusinessException("选项不是合法的 JSON 数组");
        }
        if (root == null || !root.isArray() || root.size() < 2) {
            throw new BusinessException("选项至少要有两个，且必须是 JSON 数组");
        }
        List<String> keys = new ArrayList<>();
        for (JsonNode node : root) {
            String key = node.path("key").asText("").trim().toUpperCase();
            String text = node.path("text").asText("").trim();
            if (key.isEmpty()) {
                throw new BusinessException("选项缺少 key");
            }
            if (text.isEmpty()) {
                throw new BusinessException("选项 " + key + " 的内容是空的");
            }
            if (keys.contains(key)) {
                throw new BusinessException("选项 key 重复: " + key);
            }
            keys.add(key);
        }
        return keys;
    }
}
