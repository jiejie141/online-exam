package com.jiejie.exam.exam.importing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jiejie.exam.grading.GradingRule;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 题目去重指纹。
 *
 * <p><b>为什么不能直接拿题干原文做哈希</b>：同一条题在不同来源里的标点、空格、
 * 全角半角几乎必然不同。C-Eval 的题干结尾有句号，Excel 里老师录的没有；
 * 数据集里是「（ ）」，手工录成「(  )」。原文哈希会把它们判成两道题，
 * 于是同一道题在题库里躺两份，自动组卷抽中两次，学生在同一张卷子上看到两遍。
 *
 * <p>所以先做一层归一化再哈希。归一化只做「不影响语义」的改动：
 * <ol>
 *   <li>{@link Normalizer.Form#NFKC}：全角转半角、兼容字符归一。中文题干里的
 *       「，」会变成「,」，「（）」变成「()」—— 这是我们要的。</li>
 *   <li>转小写：英文题「Which」与「which」是同一道题。</li>
 *   <li>去掉所有空白与标点：{@code 、。；：！？""''()[]{}<>-_/\\,.;:!?'"} 等。
 *       <b>这是唯一有风险的一步</b> —— 极端情况下「选 A 还是 B？」与「选 A 还是B」
 *       会被合并成同一道题。但这两者的差别本来就不构成两道不同的题，
 *       而漏合并（同一题存两份）的代价明显更高。</li>
 *   <li>选项只取文本、按文本排序后拼接，<b>不含 key</b>。选项位置换了但内容不变的，
 *       是同一道题（key 只是标签）；带上 key 会让它算出两个指纹、入库两份。
 *       见 {@link #normalizeOptions}。</li>
 * </ol>
 *
 * <p><b>刻意不把 answer 放进去</b>。如果答案参与哈希，同一道题「答案被改过」
 * 就会算出两个指纹、被当成两道题各自入库 —— 而真实情况是其中一份是错的，
 * 两份都留在题库里，组卷抽到错的那份时全班都判错。
 * 不含 answer，则第二次导入会命中同一指纹，被识别为「重复」或「答案冲突」，
 * 由导入报告点名、人工裁决。<b>宁可停在报告上，也不静默改答案。</b>
 *
 * <p><b>为什么不放 subject</b>：同一道题被两个数据集的学科分类划到不同科目是常事
 * （C-Eval 的 "college_physics" 与院系题库的 "大学物理"）。科目不同不改变题目内容，
 * 合并之后保留首次导入的科目即可 —— 想换科目是另一件事，不该靠"导入两次"实现。
 */
public final class ContentHash {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * NFKC 之后仍然要手工剔除的字符：空白 + 中英文标点 + 常见符号。
     *
     * <p>写成字符串再转 Set，而不是 {@code Set.of(...)}：后者最多 10 个元素，
     * 且重复元素会在<b>类初始化时</b>抛异常（{@code '\u3000'} 与全角空格是同一个人）。
     * 那种错不会在编译期暴露，只会在第一次调用时变成 ExceptionInInitializerError ——
     * 一个"看起来什么都没干"的类突然不可用，排查方向全错。
     */
    private static final String DROP_CHARS =
            " \t\n\r\u00A0\u3000"
                    + ",.;:!?'\"()[]{}<>-_/\\|*#@~`^&%$+="
                    + "、。；：！？“”‘’（）【】《》〈〉「」『』—…·～";

    private static final Set<Character> DROP = buildDropSet();

    private static Set<Character> buildDropSet() {
        Set<Character> set = new HashSet<>();
        for (int i = 0; i < DROP_CHARS.length(); i++) {
            set.add(DROP_CHARS.charAt(i));
        }
        return Set.copyOf(set);
    }

    private ContentHash() {
    }

    /**
     * 算指纹。{@code type} 必须传 QuestionType.code() 的值（single / multiple / judge）。
     *
     * <p>为什么题型要参与：同一段文字既可能是单选也可能是多选（「下列说法正确的有」），
     * 题型不同意味着答案判定规则不同，不能算同一道题。
     *
     * @throws IllegalArgumentException options 不是合法 JSON 数组（调用前应当已经过
     *         {@code QuestionRule} 校验；这里再抛一次是因为指纹错了比抛异常更糟）
     */
    public static String of(String type, String stem, String options) {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("content hash 需要题型");
        }
        if (stem == null || stem.isBlank()) {
            throw new IllegalArgumentException("content hash 需要题干");
        }
        StringBuilder sb = new StringBuilder(64);
        sb.append(type.trim().toLowerCase(Locale.ROOT)).append('\u0001');
        sb.append(normalizeText(stem));
        if (options != null && !options.isBlank()) {
            sb.append('\u0001');
            sb.append(normalizeOptions(options));
        }
        return sha256Hex(sb.toString());
    }

    /** 题干归一化。public 是为了让测试能直接断言"这两种写法算出同一个值"。 */
    public static String normalizeText(String raw) {
        String s = Normalizer.normalize(raw, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (DROP.contains(c)) {
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    /**
     * 选项归一化：只取<b>文本</b>，按文本排序后拼接，<b>不含 key</b>。
     *
     * <p>为什么把 key 排除在外：key 只是标签。同一个来源把选项从 [甲,乙,丙,丁]
     * 重排成 [丁,丙,乙,甲] 并把答案一起重映射，这是一道题，不是两道。
     * 带上 key 会让它算出两个指纹，于是同一道题入库两次。
     *
     * <p>按文本排序（而不是按 key）是为了让"换了位置"的同一道题落到同一个值上；
     * 用 TreeSet 保证与输入顺序无关。
     */
    private static String normalizeOptions(String options) {
        JsonNode root;
        try {
            root = JSON.readTree(options);
        } catch (Exception e) {
            throw new IllegalArgumentException("选项不是合法的 JSON，无法计算指纹", e);
        }
        if (root == null || !root.isArray()) {
            throw new IllegalArgumentException("选项必须是 JSON 数组");
        }
        TreeSet<String> texts = new TreeSet<>();
        for (JsonNode node : root) {
            String key = node.path("key").asText("").trim();
            if (key.isEmpty()) {
                throw new IllegalArgumentException("选项缺少 key，无法计算指纹");
            }
            texts.add(normalizeText(node.path("text").asText("")));
        }
        return String.join("\u0001", texts);
    }

    /**
     * 把"选中的选项"解析成它的<b>文本内容</b>，用于跨来源比对答案。
     *
     * <p><b>为什么答案不能用字母直接比</b>：两个来源的选项顺序往往不同。
     * 同一道题，A 来源是 [甲,乙] 答案 A，B 来源是 [乙,甲] 答案 B ——
     * 字母 A ≠ B，但它们指的是同一个选项（甲）。按字母比会报出一个
     * 根本不存在的"答案冲突"，而这种假冲突一多，真冲突就没人看了。
     *
     * <p>所以比对一律落到"选中了哪些内容的选项"这一层。
     * 选项文本里的标点/全角差异同样先归一化，理由同 {@link #normalizeText}。
     *
     * @param optionsJson 选项 JSON；判断题传 null
     * @param answer      答案（字母串，或判断题的 T/F）
     * @return 排序后的选中项文本，以 {@code \u0001} 分隔；解析失败返回 null（调用方退回按字母比）
     */
    public static String selectedTexts(String optionsJson, String answer) {
        if (optionsJson == null || optionsJson.isBlank()) {
            return null;
        }
        JsonNode root;
        try {
            root = JSON.readTree(optionsJson);
        } catch (Exception e) {
            return null;
        }
        if (root == null || !root.isArray()) {
            return null;
        }
        Map<String, String> keyToText = new HashMap<>();
        for (JsonNode node : root) {
            String key = node.path("key").asText("").trim().toUpperCase(Locale.ROOT);
            if (!key.isEmpty()) {
                keyToText.put(key, normalizeText(node.path("text").asText("")));
            }
        }
        if (answer == null || answer.isBlank()) {
            return "";
        }
        TreeSet<String> picked = new TreeSet<>();
        String normalized = GradingRule.normalize(answer);
        for (int i = 0; i < normalized.length(); i++) {
            String text = keyToText.get(String.valueOf(normalized.charAt(i)));
            if (text == null) {
                // 答案里有选项表里没有的字母：这道题本身就是坏的，
                // 交给 QuestionRule 在别处报错，这里不做第二套判断
                return null;
            }
            picked.add(text);
        }
        return String.join("\u0001", picked);
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必须提供的算法，走不到这里；包成 RuntimeException
            // 是为了不把受检异常传染给每个调用点
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }
}
