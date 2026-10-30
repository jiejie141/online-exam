package com.jiejie.exam.grading;

import com.jiejie.exam.exam.QuestionType;

import java.util.Set;
import java.util.TreeSet;

/**
 * 判分规则。写成纯函数是为了能被完整单测 ——
 * 判分算错是这类系统里最不可接受的 bug，而纯函数是唯一能被穷举验证的形态。
 *
 * <p>规则（对齐真实考试的阅卷习惯）：
 * <pre>
 *   单选 / 判断  完全一致 = 满分，否则 0 分
 *   多选         完全一致         = 满分
 *                非空真子集（漏选）= 半分
 *                含错误选项（错选）= 0 分
 *                未作答            = 0 分
 * </pre>
 *
 * <p>两个必须明确的细节：
 * <ul>
 *   <li><b>多选顺序无关</b>："BA" 与 "AB" 是同一个答案，必须先归一化再比。</li>
 *   <li><b>漏选给半分是"给分但不算对"</b>：correct 仍为 false，
 *       分数体现在 scoreGot 上。把漏选标成 correct 会让"正确率"统计虚高。</li>
 * </ul>
 */
public final class GradingRule {

    private GradingRule() {
    }

    /** correct = 完全正确；scoreGot = 实际得分（漏选时为半分） */
    public record Result(boolean correct, int scoreGot) {
    }

    public static Result grade(QuestionType type, String correctAnswer, String userAnswer, int fullScore) {
        if (fullScore < 0) {
            throw new IllegalArgumentException("fullScore must not be negative");
        }
        if (userAnswer == null || userAnswer.isBlank()) {
            // 未作答
            return new Result(false, 0);
        }
        if (correctAnswer == null || correctAnswer.isBlank()) {
            // 题目本身没配答案：不该发生，但不能因此把学生判成错
            throw new IllegalStateException("question has no correct answer configured");
        }

        return switch (type) {
            case SINGLE, JUDGE -> normalize(correctAnswer).equals(normalize(userAnswer))
                    ? new Result(true, fullScore)
                    : new Result(false, 0);
            case MULTIPLE -> gradeMultiple(correctAnswer, userAnswer, fullScore);
        };
    }

    private static Result gradeMultiple(String correctAnswer, String userAnswer, int fullScore) {
        Set<String> correct = toSet(correctAnswer);
        Set<String> user = toSet(userAnswer);

        if (user.isEmpty()) {
            return new Result(false, 0);
        }
        // 选了任何正确选项之外的项 -> 整题 0 分（错选不倒扣，但不给分）
        if (!correct.containsAll(user)) {
            return new Result(false, 0);
        }
        if (user.size() == correct.size()) {
            return new Result(true, fullScore);
        }
        // 漏选：非空真子集，给一半，向下取整避免出现 0.5 分的分数
        return new Result(false, fullScore / 2);
    }

    /**
     * 归一化：去掉空白与分隔符、统一大写、排序。
     * 学生端可能提交 "A,B" / "a b" / "BA" 等多种形态，都必须落到同一个答案上。
     *
     * <p>公开是为了让出题校验（{@code QuestionRule}）复用同一套规则：
     * 如果入库时按一种规则判"答案合法"、判分时按另一种规则比对，
     * 就会造出"入库通过、但全班都答不对"的题 —— 而且它不会报错。
     */
    public static String normalize(String answer) {
        StringBuilder sb = new StringBuilder();
        for (String s : toSet(answer)) {
            sb.append(s);
        }
        return sb.toString();
    }

    private static Set<String> toSet(String answer) {
        // 用 TreeSet 而不是 LinkedHashSet：归一化必须产生"与输入顺序无关"的结果，
        // 否则 "BA" 与 "AB" 会得到 "BA" / "AB" 两种规范形式，
        // 一旦有人把 normalize 的结果存起来或拿去做字符串比较就会出错。
        // （多选判分本身走 Set 比较，不受顺序影响，但归一化不能依赖这个前提。）
        Set<String> set = new TreeSet<>();
        if (answer == null) {
            return set;
        }
        for (char c : answer.toUpperCase().toCharArray()) {
            if (c == ' ' || c == ',' || c == '，' || c == ';' || c == '|') {
                continue;
            }
            set.add(String.valueOf(c));
        }
        set.remove("");
        return set;
    }
}
