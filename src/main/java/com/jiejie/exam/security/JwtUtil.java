package com.jiejie.exam.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

/**
 * JWT 签发与校验。
 *
 * <p>为什么不用 Session：考试系统会部署多份，Session 要么做粘滞要么做共享存储；
 * JWT 把"我是谁、我是学生还是老师"放进签名过的令牌里，服务端不存状态就能鉴权。
 */
@Component
public class JwtUtil {

    /** HS384 的密钥长度底线。短于此长度的密钥可被暴力枚举，签名就形同虚设。 */
    private static final int MIN_SECRET_BYTES = 32;

    private final SecretKey key;
    private final long ttlMinutes;

    public JwtUtil(@Value("${exam.jwt.secret}") String secret,
                   @Value("${exam.jwt.ttl-minutes}") long ttlMinutes) {
        // 启动即校验，不满足就拒绝启动（fail-closed）。
        //
        // 为什么在这里抛，而不是"记一条 WARN 继续跑"：
        // 一个用默认/空密钥跑起来的考试系统，从外面看和正常的一模一样 ——
        // 登录能登、判分能判、健康检查是 UP。它唯一的区别是
        // **任何人都能伪造老师的令牌**。这种失败不会自己暴露，
        // 所以只能靠"起不来"来暴露它。代价是首次部署多一步配环境变量，
        // 收益是这一步没法被跳过。
        String value = secret == null ? "" : secret.trim();
        if (value.isEmpty()) {
            throw new IllegalStateException(
                    "未配置 JWT 签名密钥：请设置环境变量 EXAM_JWT_SECRET（至少 "
                            + MIN_SECRET_BYTES + " 字节随机值）。生成方式：openssl rand -hex 32");
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "EXAM_JWT_SECRET 太短（" + bytes.length + " 字节，至少需要 " + MIN_SECRET_BYTES
                            + " 字节）：短密钥可以被暴力枚举，等同于没有签名。"
                            + "生成方式：openssl rand -hex 32");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.ttlMinutes = ttlMinutes;
    }

    public String create(Principal principal) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(principal.userId()))
                .claim("username", principal.username())
                .claim("role", principal.role())
                .claim("realName", principal.realName())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttlMinutes, ChronoUnit.MINUTES)))
                .signWith(key)
                .compact();
    }

    /** 令牌过期或签名不对时抛 JwtException，由过滤器转成 401 */
    public Principal parse(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        return new Principal(
                Long.parseLong(claims.getSubject()),
                claims.get("username", String.class),
                claims.get("role", String.class),
                claims.get("realName", String.class));
    }
}
