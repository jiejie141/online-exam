package com.jiejie.exam.domain;

import com.jiejie.exam.domain.dto.ExamScoreStats;
import com.jiejie.exam.domain.dto.QuestionAccuracy;
import com.jiejie.exam.domain.dto.ScoreBucket;
import com.jiejie.exam.domain.mapper.ExamAnswerMapper;
import com.jiejie.exam.domain.mapper.ExamRecordMapper;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 统计类 SQL 的列别名必须能落到 DTO 的字段上。
 *
 * <p>这里钉的是一个"跑通接口也看不出来"的 bug：MyBatis 的
 * map-underscore-to-camel-case 只把 snake_case 转成驼峰，
 * 如果 SQL 写的是 attempt_count 而 DTO 字段叫 attempt，两边对不上时
 * **MyBatis 不抛异常**，只是把 attempt 留成 null。表现是接口 HTTP 200、
 * JSON 里有 data、每行都有 correctCount，唯独"作答人数"整列空白、
 * "正确率"整列显示 "—"。
 *
 * <p>这个 bug 真的发生过（2026-10-23 浏览器实测老师端成绩统计时发现），
 * 当时的 e2e 脚本只断言了 correctCount，完全没看 attempt，所以一直是绿的。
 * 光补一条断言只能挡住这一处，所以这里改成对**所有** @Select 别名做静态核对：
 * 以后再加统计 SQL，别名写错会直接红在 mvn test，而不是等页面空白。
 *
 * <p>为什么用反射读注解而不是连数据库：这一层要保证的是"SQL 别名 ↔ DTO 字段"
 * 的对应关系，跟数据无关，纯静态就能判定，不需要 MySQL 参与。
 */
class MapperAliasTest {

    /** 匹配 SQL 里的列别名：AS xxx / as xxx */
    private static final Pattern ALIAS = Pattern.compile("\\bAS\\s+([A-Za-z_][A-Za-z0-9_]*)", Pattern.CASE_INSENSITIVE);

    @Test
    void 逐题正确率的每个别名都能落到QuestionAccuracy的字段上() {
        assertAliases(ExamAnswerMapper.class, "accuracyByExam", QuestionAccuracy.class);
    }

    @Test
    void 成绩概览的每个别名都能落到ExamScoreStats的字段上() {
        assertAliases(ExamRecordMapper.class, "stats", ExamScoreStats.class);
    }

    @Test
    void 成绩分布的每个别名都能落到ScoreBucket的字段上() {
        assertAliases(ExamRecordMapper.class, "distribution", ScoreBucket.class);
    }

    /**
     * 正向用例：确认上面三个方法确实被扫到了别名。
     *
     * <p>没有它的话，万一哪天有人把 @Select 改成 XML 映射，或者正则写坏了，
     * 前三个测试会因为"扫不到别名"而空跑通过 —— 那就成了假绿灯。
     */
    @Test
    void 确实扫到了别名而不是空跑() {
        int total = aliasesOf(ExamAnswerMapper.class, "accuracyByExam").size()
                + aliasesOf(ExamRecordMapper.class, "stats").size()
                + aliasesOf(ExamRecordMapper.class, "distribution").size();
        assertTrue(total >= 8, "应该至少扫到 8 个别名，实际 " + total + " 个 —— 检查正则或注解写法");
    }

    /** 把断言本身也验一遍：故意传一个不存在的字段名，必须被认出来。 */
    @Test
    void 不存在的别名会被判定为问题() {
        List<String> problems = unresolved(List.of("attempt_count", "avg_score"), QuestionAccuracy.class);
        assertFalse(problems.isEmpty(), "attempt_count 在 QuestionAccuracy 上不存在，应该被判为问题");
        assertTrue(problems.get(0).contains("attemptCount"),
                "报错要说清是转成 attemptCount 之后找不到，而不是只丢一个原始列名：" + problems.get(0));
    }

    // ---------------- 下面是工具方法 ----------------

    private static void assertAliases(Class<?> mapper, String method, Class<?> dto) {
        Set<String> aliases = aliasesOf(mapper, method);
        assertFalse(aliases.isEmpty(), mapper.getSimpleName() + "." + method + " 上没扫到任何 AS 别名");

        List<String> problems = unresolved(new ArrayList<>(aliases), dto);
        if (!problems.isEmpty()) {
            fail(mapper.getSimpleName() + "." + method + " 的 SQL 别名对不上 "
                    + dto.getSimpleName() + " 的字段：\n  " + String.join("\n  ", problems)
                    + "\n（MyBatis 遇到对不上的别名不报错，只会把字段留成 null，"
                    + "表现为页面上整列空白）");
        }
    }

    private static Set<String> aliasesOf(Class<?> mapper, String method) {
        Method m;
        try {
            m = findMethod(mapper, method);
        } catch (NoSuchMethodException e) {
            throw new AssertionError(mapper.getSimpleName() + " 上找不到方法 " + method, e);
        }
        Select select = m.getAnnotation(Select.class);
        if (select == null) {
            throw new AssertionError(mapper.getSimpleName() + "." + method + " 不是 @Select 方法");
        }
        Set<String> aliases = new LinkedHashSet<>();
        for (String sqlPart : select.value()) {
            Matcher matcher = ALIAS.matcher(sqlPart);
            while (matcher.find()) {
                aliases.add(matcher.group(1));
            }
        }
        return aliases;
    }

    private static Method findMethod(Class<?> type, String name) throws NoSuchMethodException {
        for (Method m : type.getDeclaredMethods()) {
            if (m.getName().equals(name)) {
                return m;
            }
        }
        throw new NoSuchMethodException(name);
    }

    private static List<String> unresolved(List<String> aliases, Class<?> dto) {
        List<String> problems = new ArrayList<>();
        for (String alias : aliases) {
            String camel = camelize(alias);
            if (!hasField(dto, camel)) {
                problems.add("SQL 别名 " + alias + " → 驼峰 " + camel
                        + " ，但 " + dto.getSimpleName() + " 上没有这个字段");
            }
        }
        return problems;
    }

    private static boolean hasField(Class<?> type, String name) {
        for (Field f : type.getDeclaredFields()) {
            if (f.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** avg_score → avgScore；question_id → questionId；stem 原样返回 */
    private static String camelize(String alias) {
        String lower = alias.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        boolean upperNext = false;
        for (char c : lower.toCharArray()) {
            if (c == '_') {
                upperNext = true;
            } else if (upperNext) {
                sb.append(Character.toUpperCase(c));
                upperNext = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
