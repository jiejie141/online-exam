package com.jiejie.exam.job;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 给「定时任务 + 分布式锁」这组测试造一个内存里的假 Redis。
 *
 * <p>为什么不用 {@code @Mock JobLock}：那样 {@code runIfLocked} 就是个空壳，
 * 任务里到底有没有套锁、套的是哪个名字，测试都看不出来。用真的 JobLock
 * 才能钉住接线本身 —— 比如「忘了给 AutoSubmitJob 加锁」这种改动会真的变红。
 *
 * <p>为什么不用 Mockito 的 {@code @Mock} 加 {@code @InjectMocks}：
 * {@code JobLock} 的构造函数里有 {@code RedisScript.of(...)}，那是个静态工厂，
 * 注入进来的是 mock 的 StringRedisTemplate，不是真的连接，够用了。
 */
final class JobLockTestSupport {

    private JobLockTestSupport() {
    }

    /** 永远抢得到锁：setIfAbsent 返回 true，execute 返回 1L */
    static JobLock lockThatAlwaysAcquires() {
        return new JobLock(stubRedis(true));
    }

    /** 永远抢不到锁：setIfAbsent 返回 false（模拟另一个实例正持有） */
    static JobLock lockHeldBySomeoneElse() {
        return new JobLock(stubRedis(false));
    }

    /** Redis 直接抛异常：模拟缓存挂掉，此时 JobLock 应降级为无锁执行 */
    static JobLock lockWithBrokenRedis() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        doReturn(ops).when(redis).opsForValue();
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("连接被拒绝"));
        return new JobLock(redis);
    }

    @SuppressWarnings("unchecked")
    private static StringRedisTemplate stubRedis(boolean acquired) {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        doReturn(ops).when(redis).opsForValue();
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(acquired);
        when(redis.execute(any(RedisScript.class), any(List.class), any(Object[].class))).thenReturn(1L);
        return redis;
    }
}
