package com.jiejie.exam.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 生产环境的配置体检：<b>起不来，好过带着弱口令跑起来。</b>
 *
 * <p>为什么要有这个类：{@code application-prod.yml} 里每个凭据都不给默认值，
 * 少一个环境变量 Spring 就会启动失败 —— 这一点已经挡住了「忘了配」。
 * 但它挡不住「配了，配的是 root/root」：那样的部署能正常启动、正常跑，
 * 直到某天有人从外网连上了 3306。
 *
 * <p>这类问题的共同点是<b>不会报错</b>。所以只能在启动时主动检查，
 * 让它在「刚发版」这个最容易回滚的时刻失败，而不是在「三个月后」这个最难定位的时刻。
 *
 * <p>只在 {@code prod} profile 下生效：本地开发用 root/root 连 docker compose
 * 里的 MySQL 是完全正常的，那里没有外网暴露面。
 */
@Configuration
@Profile("prod")
public class ProdConfigGuard {

    private static final Logger log = LoggerFactory.getLogger(ProdConfigGuard.class);

    /** JWT 密钥的最小字节数，与 JwtUtil.MIN_SECRET_BYTES 保持一致 */
    private static final int MIN_SECRET_BYTES = 32;

    /**
     * 明确拒绝的口令。
     *
     * <p>这不是「弱口令字典」—— 真正做那件事需要几万条词表，而且会误伤。
     * 这里只列<b>本项目自己用过的、以及各种模板里抄来的</b>那几个：
     * 它们出现在生产配置里，几乎一定是「照抄了 docker-compose.yml 没改」。
     */
    private static final Set<String> FORBIDDEN_PASSWORDS = Set.of(
            "root", "password", "123456", "12345678", "admin", "guest",
            "changeme", "change-me", "secret", "test", "qwerty");

    private final String dbPassword;
    private final String redisPassword;
    private final String rabbitPassword;
    private final String jwtSecret;

    public ProdConfigGuard(@Value("${spring.datasource.password:}") String dbPassword,
                           @Value("${spring.data.redis.password:}") String redisPassword,
                           @Value("${spring.rabbitmq.password:}") String rabbitPassword,
                           @Value("${exam.jwt.secret:}") String jwtSecret) {
        this.dbPassword = dbPassword;
        this.redisPassword = redisPassword;
        this.rabbitPassword = rabbitPassword;
        this.jwtSecret = jwtSecret;
    }

    @PostConstruct
    void check() {
        List<String> problems = new ArrayList<>();

        checkPassword("spring.datasource.password", dbPassword, problems);
        checkPassword("spring.data.redis.password", redisPassword, problems);
        checkPassword("spring.rabbitmq.password", rabbitPassword, problems);

        // JWT 密钥的强度在 JwtUtil 里已经校验过长度，这里再查一次弱值：
        // 32 个 'a' 也能过长度检查，但那不叫密钥。
        if (jwtSecret == null || jwtSecret.isBlank()) {
            problems.add("exam.jwt.secret 为空");
        } else if (jwtSecret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            problems.add("exam.jwt.secret 短于 " + MIN_SECRET_BYTES + " 字节");
        } else if (jwtSecret.chars().distinct().count() < 8) {
            problems.add("exam.jwt.secret 字符种类太少（少于 8 种），像是占位符而不是随机串");
        }

        if (!problems.isEmpty()) {
            // 一次性把问题全列出来。只报第一个的话，改完重启又冒出下一个，
            // 一轮一轮地试 —— 部署失败本来就急，不该再让人猜。
            throw new IllegalStateException(
                    "生产配置检查未通过，拒绝启动（@Profile(\"prod\")）:\n  - "
                            + String.join("\n  - ", problems)
                            + "\n生产环境的所有凭据都必须通过环境变量注入，且不能是示例值。");
        }
        log.info("prod config check passed: credentials are not placeholder values");
    }

    private static void checkPassword(String key, String value, List<String> problems) {
        if (value == null || value.isBlank()) {
            problems.add(key + " 为空");
            return;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (FORBIDDEN_PASSWORDS.contains(normalized)) {
            problems.add(key + " 是示例口令（" + value + "），必须换成真实密码");
        } else if (value.length() < 8) {
            problems.add(key + " 短于 8 个字符");
        }
    }
}
