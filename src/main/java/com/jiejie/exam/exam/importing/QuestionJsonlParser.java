package com.jiejie.exam.exam.importing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jiejie.exam.exam.QuestionRule;
import com.jiejie.exam.exam.QuestionSource;
import com.jiejie.exam.exam.QuestionType;
import com.jiejie.exam.grading.GradingRule;
import com.jiejie.exam.web.BusinessException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 把 JSONL 文本解析成 {@link QuestionDraft} 列表。
 *
 * <p><b>为什么是 JSONL 而不是 Excel 或某个数据集的原始格式</b>：
 * 原始格式有一百种（C-Eval 是嵌套 JSON + 单独一张答案表、MMLU 是 CSV 的
 * 列里塞 JSON、教材是 Word）。如果导入器去认这些格式，每接一个新来源就要改一次
 * Java 代码并重新发布 —— 而数据来源恰恰是最常变的东西。
 *
 * <p>所以边界划在这里：<b>把"认别人格式"的部分推到仓库外的脚本里</b>
 * （{@code scripts/convert_question_bank.py}），Java 只认一种中间格式。
 * 一行一道题、一行一个错误，这就是它能给出的全部承诺。
 *
 * <p><b>宽容与严格的分界线</b>（这条线决定了这个类是否可用）：
 * <ul>
 *   <li>宽容：字段别名（{@code stem/question/text/content}）、选项三种写法
 *       （对象数组 / 字符串数组 / map）、答案三种写法（字母串 / 数组 / 数字下标）、
 *       缺 {@code score} 用默认值、缺 {@code type} 按答案长度推断。
 *       这些都是<b>同一语义的不同表达</b>，不接受只是没必要的摩擦。</li>
 *   <li>严格：选项 key 必须是单个 A–Z 字母（判分器按字符比对，多字母 key 永远对不上）、
 *       数字答案必须是 1 起的下标（0 起会导致整卷错位，宁可报错也不猜）、
 *       判断题答案只能是 T/F（中文"正确"会映射，其余一律拒）、
 *       题干不超过库字段长度（超了会被 MySQL 截断或报错，都在导入后才发现）。</li>
 * </ul>
 *
 * <p><b>为什么不用异常中断解析</b>：一条坏数据不该让 4999 条好数据白解析。
 * 逐行收集问题、最后一起返回，dryRun 才能给出"到底哪几条有问题"的完整答案。
 * 真正的 fail-closed 发生在写库之前（见 {@code QuestionImportService}）。
 */
public final class QuestionJsonlParser {

    /** 单次导入上限。不是技术限制，是"出了问题能人工过一遍"的上限 */
    public static final int MAX_LINES = 5000;

    /** 报告里最多列 50 条问题，再多就没人看了 */
    static final int MAX_ISSUES = 50;

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    /** 判断题的中文/符号写法 —— 真实 Excel 里全是这些，不映射就得逐条手工改 */
    private static final Map<String, String> JUDGE_TRUE = Map.of(
            "T", "T", "TRUE", "T", "对", "T", "正确", "T", "是", "T", "√", "T", "✓", "T", "Y", "T");
    private static final Map<String, String> JUDGE_FALSE = Map.of(
            "F", "F", "FALSE", "F", "错", "F", "错误", "F", "否", "F", "×", "F", "✗", "F", "N", "F");

    private final QuestionSource defaultSource;
    private final int defaultScore;
    private final String defaultLicense;

    public QuestionJsonlParser(QuestionSource defaultSource, int defaultScore, String defaultLicense) {
        this.defaultSource = defaultSource == null ? QuestionSource.MANUAL : defaultSource;
        this.defaultScore = defaultScore;
        this.defaultLicense = defaultLicense;
    }

    public record ParseResult(List<QuestionDraft> drafts, List<ImportReport.Issue> issues) {
    }

    /**
     * @param jsonl 每行一个 JSON 对象；空行与以 {@code #} 开头的行被跳过（便于放注释）
     */
    public ParseResult parse(String jsonl) {
        List<QuestionDraft> drafts = new ArrayList<>();
        List<ImportReport.Issue> issues = new ArrayList<>();
        if (jsonl == null || jsonl.isBlank()) {
            issues.add(new ImportReport.Issue(0, "输入是空的：需要每行一个 JSON 对象"));
            return new ParseResult(drafts, issues);
        }

        // 去掉 UTF-8 BOM：Excel「另存为 CSV/文本」会带上它，
        // 而 Jackson 遇到 BOM 会抛 "Unexpected character"，报错位置还指向第一行第一列，
        // 看起来像是数据格式错了 —— 排查这个能花掉半小时。
        String body = jsonl.startsWith("\uFEFF") ? jsonl.substring(1) : jsonl;
        String[] lines = body.split("\r?\n", -1);

        int nonBlank = 0;
        for (int i = 0; i < lines.length; i++) {
            int lineNo = i + 1;
            String raw = lines[i].trim();
            if (raw.isEmpty() || raw.startsWith("#") || raw.startsWith("//")) {
                continue;
            }
            nonBlank++;
            if (nonBlank > MAX_LINES) {
                issues.add(new ImportReport.Issue(lineNo,
                        "单次最多导入 " + MAX_LINES + " 道题，请拆成多个文件分批导入"));
                break;
            }
            try {
                drafts.add(parseOne(lineNo, raw));
            } catch (BusinessException e) {
                issues.add(new ImportReport.Issue(lineNo, e.getMessage()));
            } catch (Exception e) {
                // 兜底：任何未预料的解析异常都要变成一行可读的问题，
                // 而不是让整个请求 500 —— 500 意味着"服务坏了"，实际是"数据坏了"
                issues.add(new ImportReport.Issue(lineNo, "解析失败: " + e.getMessage()));
            }
        }
        return new ParseResult(drafts, issues);
    }

    // ------------------------------------------------------------------
    // 单行解析
    // ------------------------------------------------------------------

    private QuestionDraft parseOne(int lineNo, String raw) {
        JsonNode node;
        try {
            node = JSON.readTree(raw);
        } catch (Exception e) {
            throw new BusinessException("不是合法的 JSON: " + firstLine(e.getMessage()));
        }
        if (node == null || !node.isObject()) {
            throw new BusinessException("每行必须是一个 JSON 对象，如 {\"subject\":\"Java\",\"type\":\"single\",...}");
        }

        String subject = requireText(node, "subject", "course", "discipline", "科目");
        String stem = requireText(node, "stem", "question", "text", "content", "题干");
        if (stem.length() > 1000) {
            // 库里是 VARCHAR(1000)。超长在插入时才炸，而且 MySQL 严格模式报的
            // "Data too long for column 'stem'" 不会告诉你是第几行 —— 提前拦住。
            throw new BusinessException("题干 " + stem.length() + " 字，超过库字段上限 1000 字");
        }
        if (subject.length() > 64) {
            throw new BusinessException("科目名超过 64 字: " + subject);
        }

        List<Option> rawOptions = parseOptions(node);
        String rawAnswer = extractAnswerText(node);

        String typeCode = optionalText(node, "type", "kind", "题型");
        QuestionType type = typeCode == null
                ? inferType(rawOptions, rawAnswer)
                : parseType(typeCode);

        // ---- 判断题：选项必须在库里是 NULL ----
        String optionsJson = null;
        String answer;
        if (type == QuestionType.JUDGE) {
            dropJudgeOptions(rawOptions);
            answer = normalizeJudgeAnswer(rawAnswer);
        } else {
            // ---- 选择题：重排 key 为 A,B,C… 并把答案一起映射 ----
            Rekeyed rekeyed = rekey(rawOptions);
            optionsJson = toJson(rekeyed.options());
            answer = remapAnswer(rawAnswer, rekeyed);
        }

        int score = node.hasNonNull("score") ? node.get("score").asInt()
                : node.hasNonNull("points") ? node.get("points").asInt()
                : defaultScore;
        if (score <= 0 || score > 100) {
            throw new BusinessException("分值必须在 1..100 之间，实际 " + score);
        }

        int difficulty = node.hasNonNull("difficulty") ? node.get("difficulty").asInt()
                : node.hasNonNull("level") ? node.get("level").asInt()
                : 1;
        if (difficulty < 1 || difficulty > 3) {
            // 难度是 1/2/3 三档（库注释写的），不是 1-5 或 1-100。
            // 别的系统常用 1-5，多出来的 4/5 会让"按难度抽题"永远抽不到，
            // 却看不出哪里错了。
            throw new BusinessException("难度只能是 1/2/3，实际 " + difficulty);
        }

        // 复用出题时那套校验：选项至少两个、key 不重复、答案必须在选项里……
        // 判分器认的就是这些规则，导入必须过同一道闸门，否则会造出"全班 0 分"的题
        QuestionRule.validate(type, optionsJson, answer);

        String sourceCode = optionalText(node, "source", "from");
        QuestionSource source;
        try {
            source = QuestionSource.of(sourceCode == null ? defaultSource.code() : sourceCode);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(e.getMessage());
        }

        String license = optionalText(node, "license", "licence");
        String hash = ContentHash.of(type.code(), stem, optionsJson);

        return new QuestionDraft(lineNo, subject.trim(), type.code(), stem.trim(), optionsJson,
                answer, score, difficulty,
                optionalText(node, "external_id", "externalId", "qid", "id"),
                source.code(),
                optionalText(node, "source_ref", "sourceRef", "origin", "ref"),
                license == null ? defaultLicense : license,
                hash);
    }

    // ------------------------------------------------------------------
    // 字段提取
    // ------------------------------------------------------------------

    private static String requireText(JsonNode node, String... names) {
        String v = optionalText(node, names);
        if (v == null || v.isBlank()) {
            throw new BusinessException("缺少字段 " + names[0]
                    + "（也接受别名 " + String.join(" / ", List.of(names).subList(1, names.length)) + "）");
        }
        return v.trim();
    }

    private static String optionalText(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode v = node.get(name);
            if (v == null || v.isNull()) {
                continue;
            }
            String s = v.isTextual() ? v.asText() : v.asText(null);
            if (s != null && !s.isBlank()) {
                return s.trim();
            }
        }
        return null;
    }

    private static QuestionType parseType(String code) {
        try {
            return QuestionType.of(code.trim().toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException("题型只能是 single / multiple / judge，实际: " + code);
        }
    }

    /**
     * 缺 type 时按形状推断。这是"宽容但可解释"的一处：
     * 有选项 → 答案多于一个字母就是多选，否则单选；没选项 → 判断。
     *
     * <p>为什么敢推断：推断结果仍要过 {@code QuestionRule}，
     * 推错了会被下一道闸门挡下并给出具体原因，不会静默入库。
     */
    private static QuestionType inferType(List<Option> options, String answer) {
        if (options.isEmpty()) {
            return QuestionType.JUDGE;
        }
        String normalized = GradingRule.normalize(answer);
        return normalized.length() > 1 ? QuestionType.MULTIPLE : QuestionType.SINGLE;
    }

    // ------------------------------------------------------------------
    // 选项
    // ------------------------------------------------------------------

    private record Option(String key, String text) {
    }

    /**
     * 三种写法都要认：
     * <pre>
     *   [{"key":"A","text":"甲"}, ...]        数据集与导出脚本用的规范形式
     *   ["甲","乙","丙"]                       Excel / 简单 JSON，key 按位置补 A,B,C…
     *   {"A":"甲","B":"乙"}                    早期题库的 map 形式
     * </pre>
     */
    private static List<Option> parseOptions(JsonNode node) {
        JsonNode raw = firstPresent(node, "options", "choices", "option_list", "optionList");
        if (raw == null || raw.isNull()) {
            return List.of();
        }
        List<Option> out = new ArrayList<>();
        if (raw.isArray()) {
            for (int i = 0; i < raw.size(); i++) {
                JsonNode item = raw.get(i);
                if (item.isTextual()) {
                    if (i >= LETTERS.length()) {
                        throw new BusinessException("选项超过 26 个，无法用单字母编号");
                    }
                    out.add(new Option(String.valueOf(LETTERS.charAt(i)), item.asText("")));
                } else if (item.isObject()) {
                    String key = item.path("key").asText("").trim();
                    String text = item.path("text").asText(null);
                    if (text == null) {
                        text = item.path("value").asText(null);
                    }
                    if (text == null) {
                        text = item.path("label").asText(null);
                    }
                    if (key.isEmpty()) {
                        key = String.valueOf(LETTERS.charAt(Math.min(i, LETTERS.length() - 1)));
                    }
                    out.add(new Option(key, text == null ? "" : text));
                } else if (item.isNumber()) {
                    // 形如 [1,2,3,4] 的"编号"没有语义，直接拒绝比猜一个更好
                    throw new BusinessException("选项是数字数组，缺少选项文本");
                } else {
                    throw new BusinessException("无法识别的选项形式: " + item.getNodeType());
                }
            }
            return out;
        }
        if (raw.isObject()) {
            // 用 fields() 而不是 properties()：前者在 Jackson 2.13~2.18 全都有，
            // 后者是 2.15 才加的。这个项目没有锁死 Jackson 版本，不值得为一行遍历
            // 引入一个版本敏感的 API。
            raw.fields().forEachRemaining(e -> out.add(new Option(e.getKey(), e.getValue().asText(""))));
            return out;
        }
        throw new BusinessException("选项必须是数组或对象，实际: " + raw.getNodeType());
    }

    /**
     * 把选项 key 统一重排成 A,B,C…（按原顺序）。
     *
     * <p>为什么必须重排而不是原样保留：判分器 {@code GradingRule} 按<b>单字符</b>比对答案，
     * 一个 key 是 "选项一" 或 "1"、"①" 的题库，学生提交的答案永远拼不出能对上的集合。
     * 重排之后，答案也随之通过 {@code remap} 映射，语义不变。
     *
     * <p>副作用是原始的 key 会丢失 —— 这是可接受的，因为 key 只是标签，
     * 题目的语义在 text 里。但源数据的 {@code external_id} 会保留，回溯不受影响。
     */
    private static Rekeyed rekey(List<Option> options) {
        if (options.size() > LETTERS.length()) {
            throw new BusinessException("选项 " + options.size() + " 个，超过 26 个上限");
        }
        List<Option> out = new ArrayList<>(options.size());
        Map<String, String> forward = new LinkedHashMap<>();
        for (int i = 0; i < options.size(); i++) {
            String newKey = String.valueOf(LETTERS.charAt(i));
            String oldKey = options.get(i).key() == null ? "" : options.get(i).key().trim().toUpperCase(Locale.ROOT);
            if (!oldKey.isEmpty() && !forward.containsKey(oldKey)) {
                forward.put(oldKey, newKey);
            }
            out.add(new Option(newKey, options.get(i).text()));
        }
        return new Rekeyed(out, forward);
    }

    private record Rekeyed(List<Option> options, Map<String, String> keyMap) {
    }

    private static String toJson(List<Option> options) {
        ArrayNode arr = JSON.createArrayNode();
        for (Option o : options) {
            ObjectNode obj = arr.addObject();
            obj.put("key", o.key());
            obj.put("text", o.text() == null ? "" : o.text());
        }
        return arr.toString();
    }

    /** 判断题的选项：数据集给了「正确/错误」，直接丢掉即可 */
    private static void dropJudgeOptions(List<Option> options) {
        if (options.isEmpty()) {
            return;
        }
        // 只允许恰好两个、且内容是判断标签的情况。别的形态说明这条数据是选择题
        // 被错标成判断题 —— 静默丢掉选项会让题干里的「A.…B.…」失去参照，必须报错。
        boolean looksLikeJudge = options.size() == 2
                && JUDGE_TRUE.containsKey(options.get(0).text().trim().toUpperCase(Locale.ROOT))
                && JUDGE_FALSE.containsKey(options.get(1).text().trim().toUpperCase(Locale.ROOT));
        if (!looksLikeJudge) {
            throw new BusinessException("判断题不该带选项；若这是选择题，请把 type 改成 single / multiple");
        }
    }

    // ------------------------------------------------------------------
    // 答案
    // ------------------------------------------------------------------

    private static String extractAnswerText(JsonNode node) {
        JsonNode v = firstPresent(node, "answer", "correct", "correct_answer", "answer_key", "答案");
        if (v == null || v.isNull()) {
            throw new BusinessException("缺少字段 answer（也接受 correct / answer_key）");
        }
        if (v.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode item : v) {
                sb.append(item.asText(""));
            }
            return sb.toString();
        }
        return v.asText("");
    }

    private static String normalizeJudgeAnswer(String raw) {
        String key = raw.trim().toUpperCase(Locale.ROOT);
        String mapped = JUDGE_TRUE.get(key);
        if (mapped == null) {
            mapped = JUDGE_FALSE.get(key);
        }
        if (mapped == null) {
            throw new BusinessException("判断题答案只能是 T / F（也接受 正确/错误、对/错、√/×），实际: " + raw);
        }
        return mapped;
    }

    /**
     * 把"源格式的答案"映射到"重排后的选项 key"。
     *
     * <p>解析顺序（顺序不能换）：
     * <ol>
     *   <li>先按<b>原始 key</b> 查映射表 —— 这是最常见的情况，也是唯一无歧义的；</li>
     *   <li>查不到再当<b>1 起的数字下标</b>。</li>
     * </ol>
     *
     * <p><b>为什么不接受 0 起的下标</b>：0 起和 1 起差一位，而这一位的后果是
     * 整场考试所有单选题的答案都指向邻项 —— 全班分数集体偏移，但没有任何报错。
     * 与其猜，不如让 {@code convert_question_bank.py} 把下标转成字母后再导。
     * 这条约束是刻意的，不是遗漏。
     */
    private static String remapAnswer(String rawAnswer, Rekeyed rekeyed) {
        String trimmed = rawAnswer.trim().toUpperCase(Locale.ROOT)
                .replaceAll("[\\s,，;；|]", "");
        if (trimmed.isEmpty()) {
            throw new BusinessException("答案不能为空");
        }
        StringBuilder mapped = new StringBuilder();
        for (char c : trimmed.toCharArray()) {
            String token = String.valueOf(c);
            String hit = rekeyed.keyMap().get(token);
            if (hit != null) {
                mapped.append(hit);
                continue;
            }
            if (Character.isDigit(c)) {
                int idx = c - '0';
                if (idx == 0) {
                    throw new BusinessException("答案里的下标 0 无法判断是 0 起还是 1 起；"
                            + "请先用 scripts/convert_question_bank.py 把下标转成字母");
                }
                if (idx > rekeyed.options().size()) {
                    throw new BusinessException("答案下标 " + idx + " 超出选项个数 " + rekeyed.options().size());
                }
                mapped.append(rekeyed.options().get(idx - 1).key());
                continue;
            }
            // 原样保留，交给 QuestionRule 报出"答案 X 不在选项里"这种更准确的话
            mapped.append(c);
        }
        return GradingRule.normalize(mapped.toString());
    }

    private static JsonNode firstPresent(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode v = node.get(name);
            if (v != null && !v.isNull()) {
                return v;
            }
        }
        return null;
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "unknown";
        }
        int nl = message.indexOf('\n');
        return nl < 0 ? message : message.substring(0, nl);
    }
}
