package com.jiejie.exam.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 定时任务的「同一时刻只有一个实例在跑」保证。
 *
 * <p><b>为什么需要</b>：三个 {@code @Scheduled} 任务都是「扫一批 → 逐条改状态」。
 * 单实例时天然只有一个执行者；一旦部署两份（这正是 JWT 那条注释里
 * 说的"考试系统会部署多份"的场景），两个实例会同时捞到同一批行。
 *
 * <p>对 AutoSubmitJob 来说这还不算灾难 —— 它靠 {@code markSubmitted} 的
 * {@code WHERE status='ongoing'} 条件让后到者 affected rows 为 0，是幂等的。
 * 但代价是<b>一半的 MQ 消息是白发的</b>，而且「这一轮到底谁处理的」在日志里
 * 分不清。对 ExamCloseJob 则是重复 UPDATE。真正的风险在以后：
 * 谁往这里加一步「扣减名额」「发通知」，重复执行就会变成重复扣、重复发。
 *
 * <p><b>为什么不用 @Transactional 或数据库锁</b>：任务本身要跨多条记录、
 * 还要调 MQ，不是一个事务能包住的；用 {@code SELECT ... FOR UPDATE} 锁住一批行
 * 会把数据库连接按住几十秒，比重复执行更糟。
 *
 * <p><b>Redis 不可用时怎么办：照常执行。</b> 这一点是刻意的，不是遗漏。
 * 锁的作用是「省掉重复劳动」，不是「保证正确性」—— 正确性由每个任务自己的
 * 条件 UPDATE 兜底。反过来，如果 Redis 一挂任务就全部停摆，
 * 那「超时自动收卷」这种保命任务会跟着一起停，代价远大于多跑一遍。
 * 换句话说：<b>锁是优化，不是正确性前提。</b>
 */
@Component
public class JobLock {

    private static final Logger log = LoggerFactory.getLogger(JobLock.class);

    private static final String PREFIX = "exam:job:lock:";

    /** 释放锁必须用 Lua：判 token 与删除要原子，否则可能删掉别人刚抢到的锁 */
    private static final String RELEASE_LUA =
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";

    private final StringRedisTemplate redis;
    private final RedisScript<Long> releaseScript;

    public JobLock(StringRedisTemplate redis) {
        this.redis = redis;
        this.releaseScript = RedisScript.of(RELEASE_LUA, Long.class);
    }

    /**
     * 抢到锁才执行 {@code task}；没抢到直接返回 null（表示这一轮由别的实例做了）。
     *
     * @param name        任务名，只用于拼 key 和打日志
     * @param ttlSeconds  锁的存活时间。必须大于任务的正常耗时，否则任务还在跑锁就过期了，
     *                    另一个实例会同时进来 —— 那等于没加锁。宁可设长一点：
     *                    真出异常时 finally 会释放，进程被杀时最多等这么久。
     */
    public <T> T runIfLocked(String name, long ttlSeconds, Supplier<T> task) {
        String key = PREFIX + name;
        String token = UUID.randomUUID().toString();

        Boolean acquired;
        try {
            acquired = redis.opsForValue().setIfAbsent(key, token, Duration.ofSeconds(ttlSeconds));
        } catch (RuntimeException e) {
            // Redis 不可用 → 单实例假设下照常执行，理由见类注释
            log.warn("job lock {} unavailable, run without lock (single-instance assumption)", name, e);
            return task.get();
        }

        if (!Boolean.TRUE.equals(acquired)) {
            log.debug("job {} skipped, another instance holds the lock", name);
            return null;
        }
        try {
            return task.get();
        } finally {
            release(key, token);
        }
    }

    private void release(String key, String token) {
        try {
            redis.execute(releaseScript, List.of(key), token);
        } catch (RuntimeException e) {
            // 释放失败不影响本轮结果：锁有 TTL，最多让下一轮晚一点开始
            log.warn("job lock {} release failed, will expire by ttl", key, e);
        }
    }
}
