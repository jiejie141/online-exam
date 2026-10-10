package com.jiejie.exam.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JWT 密钥的 fail-closed 契约。
 *
 * <p>这组测试守的是一条**不会自己暴露**的失败：用默认密钥或空密钥跑起来的
 * 考试系统，从外面看和正常的完全一样 —— 登录能登、判分能判、健康检查是 UP，
 * 唯一的区别是任何人都能自己签一个 {@code role=teacher} 的令牌改成绩。
 *
 * <p>所以"起不来"必须是一条被钉住的契约，而不是某个构造函数里一句注释。
 * 哪天有人为了"本地方便"把默认值加回去，红的是这里。
 */
class JwtUtilTest {

    private static final String GOOD_SECRET = "0123456789abcdef0123456789abcdef"; // 32 字节

    @Test
    void rejectsEmptySecret() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new JwtUtil("", 120));
        assertTrue(e.getMessage().contains("EXAM_JWT_SECRET"), "报错要告诉人该设哪个变量");
    }

    @Test
    void rejectsNullSecret() {
        assertThrows(IllegalStateException.class, () -> new JwtUtil(null, 120));
    }

    @Test
    void rejectsBlankSecret() {
        // 只写了空格和换行的密钥在配置里看起来"有值"，实际等于没有
        assertThrows(IllegalStateException.class, () -> new JwtUtil("   \n  ", 120));
    }

    @Test
    void rejectsTooShortSecret() {
        // 短密钥可被暴力枚举，签名形同虚设 —— 这是"没配"的另一种形态
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new JwtUtil("short", 120));
        assertTrue(e.getMessage().contains("太短"), "报错要说清楚是长度问题而不是缺失");
    }

    @Test
    void acceptsAProperSecretAndRoundTrips() {
        JwtUtil jwt = new JwtUtil(GOOD_SECRET, 120);
        Principal p = new Principal(7L, "s001", "student", "学生甲");

        Principal parsed = jwt.parse(jwt.create(p));

        assertEquals(7L, parsed.userId());
        assertEquals("s001", parsed.username());
        assertEquals("student", parsed.role());
        assertEquals("学生甲", parsed.realName());
        assertNotNull(parsed);
    }

    @Test
    void rejectsTokenSignedWithADifferentSecret() {
        JwtUtil signer = new JwtUtil(GOOD_SECRET, 120);
        JwtUtil verifier = new JwtUtil("ffffffffffffffffffffffffffffffff", 120);

        // 这正是"默认密钥进了仓库"的真实后果：知道那个值的人可以签出任意身份的令牌。
        // 换掉密钥之后这条令牌必须失效。
        assertThrows(RuntimeException.class,
                () -> verifier.parse(signer.create(new Principal(1L, "t001", "teacher", "老师"))));
    }
}
