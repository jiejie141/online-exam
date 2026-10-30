package com.jiejie.exam.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生产配置体检：<b>起不来，好过带着弱口令跑起来。</b>
 *
 * <p>这个类的价值全在「失败」上 —— 它平时一声不吭，所以一旦它被改坏
 * （比如异常被 catch 掉、或者判断写反），没有任何现象会提醒你。
 * 因此每一条都必须能真的变红：这里逐个构造违规配置，断言启动被拒绝。
 */
class ProdConfigGuardTest {

    private static final String STRONG = "S7r0ng-Pa55w0rd-NotAPlaceholder";
    private static final String STRONG_JWT = "9f3c1a7e5b2d8046af13c9e70b5d2684";

    private ProdConfigGuard guard(String db, String redis, String rabbit, String jwt) {
        return new ProdConfigGuard(db, redis, rabbit, jwt);
    }

    @Test
    void 全部合规时通过() {
        assertDoesNotThrow(() -> guard(STRONG, STRONG, STRONG, STRONG_JWT).check());
    }

    @Test
    void 数据库口令是root时拒绝启动() {
        // root/root 是 docker-compose.yml 里的值。它出现在生产配置里，
        // 几乎一定是「照抄了本地配置没改」—— 而这不会报任何错。
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> guard("root", STRONG, STRONG, STRONG_JWT).check());
        assertTrue(e.getMessage().contains("spring.datasource.password"), e.getMessage());
    }

    @Test
    void Redis口令是password时拒绝启动() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> guard(STRONG, "password", STRONG, STRONG_JWT).check());
        assertTrue(e.getMessage().contains("spring.data.redis.password"), e.getMessage());
    }

    @Test
    void RabbitMQ口令是guest时拒绝启动() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> guard(STRONG, STRONG, "guest", STRONG_JWT).check());
        assertTrue(e.getMessage().contains("spring.rabbitmq.password"), e.getMessage());
    }

    @Test
    void 口令为空时拒绝启动() {
        assertThrows(IllegalStateException.class,
                () -> guard("", STRONG, STRONG, STRONG_JWT).check());
    }

    @Test
    void 口令过短时拒绝启动() {
        assertThrows(IllegalStateException.class,
                () -> guard("abc12", STRONG, STRONG, STRONG_JWT).check());
    }

    @Test
    void JWT密钥过短时拒绝启动() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> guard(STRONG, STRONG, STRONG, "short-secret").check());
        assertTrue(e.getMessage().contains("exam.jwt.secret"), e.getMessage());
    }

    @Test
    void JWT密钥是重复字符时拒绝启动() {
        // 32 个 'a' 能过长度检查，但那不叫密钥。这条钉的正是「长度不是强度」。
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> guard(STRONG, STRONG, STRONG, "a".repeat(48)).check());
        assertTrue(e.getMessage().contains("字符种类"), e.getMessage());
    }

    @Test
    void 多个问题一次性全报出来() {
        // 只报第一个的话，改完重启又冒出下一个，一轮一轮试 ——
        // 部署失败本来就急，不该再让人猜。
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> guard("root", "root", "guest", "").check());
        String msg = e.getMessage();
        assertTrue(msg.contains("spring.datasource.password"), msg);
        assertTrue(msg.contains("spring.data.redis.password"), msg);
        assertTrue(msg.contains("spring.rabbitmq.password"), msg);
        assertTrue(msg.contains("exam.jwt.secret"), msg);
    }

    @Test
    void 大小写不敏感地识别示例口令() {
        // 把 Root / ROOT 当成安全口令是很容易犯的错
        assertThrows(IllegalStateException.class,
                () -> guard("ROOT", STRONG, STRONG, STRONG_JWT).check());
    }
}
