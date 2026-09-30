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

    private final SecretKey key;
    private final long ttlMinutes;

    public JwtUtil(@Value("${exam.jwt.secret}") String secret,
                   @Value("${exam.jwt.ttl-minutes}") long ttlMinutes) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
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
