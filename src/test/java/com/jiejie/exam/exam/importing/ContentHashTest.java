package com.jiejie.exam.exam.importing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 去重指纹。
 *
 * <p>这个类的每个用例都对应一种"真实数据集之间必然存在的差异"。
 * 指纹算错的后果不是报错，而是<b>题库里静默地多出一份同一道题</b>：
 * 组卷时被抽中两次，学生在同一张卷子上看到两遍，而没有任何日志留下痕迹。
 */
class ContentHashTest {

    private static final String OPTS_AB =
            "[{\"key\":\"A\",\"text\":\"甲\"},{\"key\":\"B\",\"text\":\"乙\"}]";

    @Test
    void 标点与空格不同不影响指纹() {
        // C-Eval 的题干结尾带句号，Excel 里老师录的不带；中间还常有全角空格
        String a = ContentHash.of("single", "下列哪个是质数？", OPTS_AB);
        String b = ContentHash.of("single", "下列哪个是质数", OPTS_AB);
        String c = ContentHash.of("single", " 下列哪个是质数? ", OPTS_AB);
        assertEquals(a, b, "句号不该改变身份");
        assertEquals(a, c, "问号与空格不该改变身份");
    }

    @Test
    void 全角半角不影响指纹() {
        // NFKC 会把 （）转成 ()、，转成 ,。中文题里这两种写法混用极其常见
        String a = ContentHash.of("single", "已知函数 f（x）", OPTS_AB);
        String b = ContentHash.of("single", "已知函数 f(x)", OPTS_AB);
        assertEquals(a, b);
    }

    @Test
    void 英文大小写不影响指纹() {
        assertEquals(
                ContentHash.of("single", "Which one is prime?", OPTS_AB),
                ContentHash.of("single", "which one is PRIME", OPTS_AB));
    }

    @Test
    void 选项顺序调换不影响指纹() {
        // 同一道题，一个来源排 [甲,乙]，另一个排 [乙,甲]。key 只是标签，
        // 内容集合相同就是同一道题 —— 若不合并，题库里会躺两份
        String swapped = "[{\"key\":\"A\",\"text\":\"乙\"},{\"key\":\"B\",\"text\":\"甲\"}]";
        assertEquals(ContentHash.of("single", "选出正确的一项", OPTS_AB),
                ContentHash.of("single", "选出正确的一项", swapped));
    }

    @Test
    void 选项内容不同就是不同题() {
        String other = "[{\"key\":\"A\",\"text\":\"甲\"},{\"key\":\"B\",\"text\":\"丙\"}]";
        assertNotEquals(ContentHash.of("single", "选出正确的一项", OPTS_AB),
                ContentHash.of("single", "选出正确的一项", other));
    }

    @Test
    void 题型不同就是不同题() {
        // 同一段文字既可能是单选也可能是多选，判定规则不同，不能合并
        assertNotEquals(ContentHash.of("single", "下列说法正确的有", OPTS_AB),
                ContentHash.of("multiple", "下列说法正确的有", OPTS_AB));
    }

    @Test
    void 判断题不需要选项也能算指纹() {
        String a = ContentHash.of("judge", "水的沸点是 100 摄氏度", null);
        String b = ContentHash.of("judge", "水的沸点是100摄氏度。", null);
        assertEquals(a, b);
        assertNotEquals(a, ContentHash.of("judge", "水的沸点是 90 摄氏度", null));
    }

    @Test
    void 缺题干与缺题型都拒绝计算() {
        // 宁可抛异常也不返回一个"看起来正常"的指纹：指纹错了比没有更糟
        assertThrows(IllegalArgumentException.class, () -> ContentHash.of("single", "  ", OPTS_AB));
        assertThrows(IllegalArgumentException.class, () -> ContentHash.of(null, "题干", OPTS_AB));
    }

    @Test
    void 选项不是合法JSON时拒绝计算() {
        assertThrows(IllegalArgumentException.class,
                () -> ContentHash.of("single", "题干", "[{key:A"));
    }

    // ---------------- selectedTexts：跨来源比对答案 ----------------

    @Test
    void 答案比对落在选项文本上而不是字母上() {
        // 同一道题的两个来源：一个排 [甲,乙] 答案 A，一个排 [乙,甲] 答案 B。
        // 字母 A≠B，但选中的都是"甲" —— 按字母比会报出一个不存在的冲突
        String swapped = "[{\"key\":\"A\",\"text\":\"乙\"},{\"key\":\"B\",\"text\":\"甲\"}]";
        assertEquals(ContentHash.selectedTexts(OPTS_AB, "A"),
                ContentHash.selectedTexts(swapped, "B"));
    }

    @Test
    void 多选按集合比对与顺序无关() {
        String opts = "[{\"key\":\"A\",\"text\":\"甲\"},{\"key\":\"B\",\"text\":\"乙\"},"
                + "{\"key\":\"C\",\"text\":\"丙\"}]";
        assertEquals(ContentHash.selectedTexts(opts, "AB"), ContentHash.selectedTexts(opts, "BA"));
        assertNotEquals(ContentHash.selectedTexts(opts, "AB"), ContentHash.selectedTexts(opts, "AC"));
    }

    @Test
    void 答案字母不在选项里时返回null让调用方退回按字母比() {
        assertNull(ContentHash.selectedTexts(OPTS_AB, "Z"));
        assertNull(ContentHash.selectedTexts("[{bad json", "A"));
        assertNull(ContentHash.selectedTexts(null, "A"));
    }
}
