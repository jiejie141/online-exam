package com.jiejie.exam.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jiejie.exam.domain.dto.PaperItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 试卷缓存。一场考试开考后，几百人会在同一分钟里拉取同一份卷子，
 * 全都打到 MySQL 上没意义 —— 卷子在发布后就不会再变，适合整份缓存。
 *
 * <p>这里逐个处理缓存的三个经典问题，每个都有对应的代码位置：
 * <ol>
 *   <li><b>穿透</b>：查一个不存在的 examId，每次都会落到数据库。
 *       解法是"空值也缓存"—— 存一个哨兵串 {@link #NULL_SENTINEL}，短暂 TTL。</li>
 *   <li><b>击穿</b>：一个热点 key 过期的瞬间，几百个请求同时发现没缓存，
 *       同时去重建。解法是重建前先抢锁 {@link #lock}，抢到的才去查库。</li>
 *   <li><b>雪崩</b>：一批 key 在同一秒集体过期，流量同时砸到数据库。
 *       解法是给 TTL 加随机抖动 {@link #jitteredTtl}，把过期时刻打散。</li>
 * </ol>
 */
@Component
public class PaperCache {

    private static final Logger log = LoggerFactory.getLogger(PaperCache.class);

    /** 空值哨兵。不能存空字符串：部分 Redis 客户端会把空串和"不存在"搞混 */
    static final String NULL_SENTINEL = "__NULL__";

    private static final String KEY_PREFIX = "exam:paper:";
    private static final String LOCK_PREFIX = "exam:paper:lock:";

    /** 释放锁必须用 Lua：判断 token 和删除要原子完成，否则可能删掉别人刚抢到的锁 */
    private static final String RELEASE_LUA =
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";

    private final StringRedisTemplate redis;
    private final RedisScript<Long> releaseScript;
    private final ObjectMapper objectMapper;

    private final long baseTtlSeconds;
    private final long nullTtlSeconds;
    private final double jitterRatio;

    /**
     * 只保留这一个构造函数。
     *
     * <p>不要为了"方便"再重载一个无参或两参的版本：多个构造函数且都没标 @Autowired 时，
     * Spring 会去找<b>默认构造函数</b>，找不到就直接启动失败
     * （NoSuchMethodException: PaperCache.<init>()）。这个坑踩过一次。
     * 默认值统一用 @Value 的 `:默认值` 语法给。
     */
    public PaperCache(StringRedisTemplate redis,
                      ObjectMapper objectMapper,
                      @Value("${exam.cache.paper-ttl-seconds:600}") long baseTtlSeconds,
                      @Value("${exam.cache.null-ttl-seconds:60}") long nullTtlSeconds,
                      @Value("${exam.cache.jitter-ratio:0.2}") double jitterRatio) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.releaseScript = RedisScript.of(RELEASE_LUA, Long.class);
        this.baseTtlSeconds = baseTtlSeconds;
        this.nullTtlSeconds = nullTtlSeconds;
        this.jitterRatio = jitterRatio;
    }

    /**
     * 命中返回卷面（可能为空 List 表示"这门考试确实没题"），未命中返回 null。
     * Redis 连不上时也返回 null —— 缓存是加速用的，不是数据的来源，
     * 它挂掉只能让取卷慢一点，不能让学生打不开卷子。
     */
    public List<PaperItem> get(Long examId) {
        String raw = safe(() -> redis.opsForValue().get(key(examId)), "get");
        if (raw == null) {
            return null;
        }
        if (NULL_SENTINEL.equals(raw)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(raw, new TypeReference<List<PaperItem>>() {
            });
        } catch (Exception e) {
            // 缓存里是坏数据：删掉，让下一次请求重新走数据库，而不是一直报错
            safe(() -> redis.delete(key(examId)), "delete-broken");
            return null;
        }
    }

    public void put(Long examId, List<PaperItem> paper) {
        if (paper == null || paper.isEmpty()) {
            // 缓存空结果，挡住穿透
            safeRun(() -> redis.opsForValue().set(key(examId), NULL_SENTINEL, nullTtlSeconds, TimeUnit.SECONDS),
                    "put-null");
            return;
        }
        String raw;
        try {
            raw = objectMapper.writeValueAsString(paper);
        } catch (Exception e) {
            throw new IllegalStateException("serialize paper failed, examId=" + examId, e);
        }
        safeRun(() -> redis.opsForValue().set(key(examId), raw,
                jitteredTtl(baseTtlSeconds, jitterRatio, Math.random()), TimeUnit.SECONDS), "put");
    }

    public void evict(Long examId) {
        safe(() -> redis.delete(key(examId)), "evict");
    }

    /** 抢重建锁。抢到返回 token（释放时要带上），没抢到返回 null */
    public String tryLock(Long examId, long waitSeconds) {
        String token = UUID.randomUUID().toString();
        Boolean ok = safe(() -> redis.opsForValue()
                .setIfAbsent(lockKey(examId), token, Duration.ofSeconds(waitSeconds)), "lock");
        return Boolean.TRUE.equals(ok) ? token : null;
    }

    public void unlock(Long examId, String token) {
        if (token == null) {
            return;
        }
        safe(() -> redis.execute(releaseScript, List.of(lockKey(examId)), token), "unlock");
    }

    /**
     * 任何 Redis 操作出错都吞掉并降级。
     *
     * <p>这不是"懒得处理异常"：开考瞬间几百人同时拉取卷子，如果 Redis 抖一下就让请求失败，
     * 教务处得重开考试。降级后的代价只是多查几次库，而考试本来也就几百人规模，扛得住。
     */
    private <T> T safe(Supplier<T> action, String actionName) {
        try {
            return action.get();
        } catch (RuntimeException e) {
            log.warn("redis {} failed, degrade to db", actionName, e);
            return null;
        }
    }

    /** 写入类操作没有返回值，单独一个重载，避免为了复用 Supplier 去写 "return null" 这种噪音 */
    private void safeRun(Runnable action, String actionName) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.warn("redis {} failed, degrade to db", actionName, e);
        }
    }

    /**
     * 给 TTL 加抖动：把 base 秒数按 ±ratio 随机打散。
     *
     * <p>写成纯函数（随机数由调用方传入）是为了能被单测钉住 ——
     * 依赖 Math.random() 的代码没法断言。
     */
    static long jitteredTtl(long baseSeconds, double ratio, double random01) {
        long span = (long) (baseSeconds * ratio);
        // random01 ∈ [0,1) 映射到 [-span, +span]
        return baseSeconds - span + (long) (random01 * (2L * span + 1));
    }

    private String key(Long examId) {
        return KEY_PREFIX + examId;
    }

    private String lockKey(Long examId) {
        return LOCK_PREFIX + examId;
    }
}
