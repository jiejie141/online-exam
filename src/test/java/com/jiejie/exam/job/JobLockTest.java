package com.jiejie.exam.job;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 定时任务分布式锁：<b>锁是优化，不是正确性前提。</b>
 *
 * <p>这个测试存在的理由，是钉住 JobLock 的类注释里那三条互相拉扯的取舍：
 * <ol>
 *   <li>抢到锁 → 执行；</li>
 *   <li>没抢到 → 跳过（这一轮由别的实例做）；</li>
 *   <li><b>Redis 挂了 → 照常执行</b>。这条最容易在重构时被"顺手改成安全优先"而丢掉，
 *       而代价是「超时自动收卷」这种保命任务跟着 Redis 一起停摆。</li>
 * </ol>
 */
class JobLockTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private JobLock lock;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        doReturn(ops).when(redis).opsForValue();
        lock = new JobLock(redis);
    }

    @Test
    void 抢到锁就执行并把返回值透传() {
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        String result = lock.runIfLocked("t", 60, () -> "done");

        assertEquals("done", result);
    }

    @Test
    void 抢不到锁就不执行返回null() {
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        AtomicBoolean ran = new AtomicBoolean(false);

        Object result = lock.runIfLocked("t", 60, () -> {
            ran.set(true);
            return "x";
        });

        assertNull(result);
        assertTrue(!ran.get(), "没抢到锁时任务体不能被执行");
    }

    @Test
    void 执行完一定释放锁() {
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        lock.runIfLocked("t", 60, () -> "done");

        // 释放走 Lua：判 token 再 del，否则可能删掉别人刚抢到的锁
        verify(redis).execute(any(RedisScript.class), eq(List.of("exam:job:lock:t")), any(Object[].class));
    }

    @Test
    void 任务抛异常也要释放锁() {
        // 不释放的后果：锁只能等 TTL 过期，这段时间整个集群都跑不了这个任务
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        assertThrows(IllegalStateException.class,
                () -> lock.runIfLocked("t", 60, () -> {
                    throw new IllegalStateException("任务炸了");
                }));

        verify(redis).execute(any(RedisScript.class), eq(List.of("exam:job:lock:t")), any(Object[].class));
    }

    @Test
    void Redis不可用时降级为无锁执行() {
        // 这条是 JobLock 的核心决定，不是遗漏：
        // 正确性由任务自己的条件 UPDATE 兜底，锁只是省重复劳动。
        // 若这里改成"Redis 挂了就跳过"，Redis 一抖动自动收卷就停，
        // 学生的卷子会永远停在 ongoing —— 比重复执行糟得多。
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RedisConnectionFailureException("连接被拒绝"));
        AtomicInteger ran = new AtomicInteger(0);

        String result = lock.runIfLocked("t", 60, () -> {
            ran.incrementAndGet();
            return "still ran";
        });

        assertEquals("still ran", result);
        assertEquals(1, ran.get());
    }

    @Test
    void Redis不可用时释放也不用试() {
        // 既然根本没抢到锁（异常发生在 setIfAbsent），就不该去 del 一个别人的 key
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RedisConnectionFailureException("连接被拒绝"));

        lock.runIfLocked("t", 60, () -> "ok");

        verify(redis, never()).execute(any(RedisScript.class), any(List.class), any(Object[].class));
    }

    @Test
    void 释放失败不影响本轮结果() {
        // 释放抛异常时任务结果必须照常返回：锁有 TTL，最多让下一轮晚点开始
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(redis.execute(any(RedisScript.class), any(List.class), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("连接被拒绝"));

        assertEquals("done", lock.runIfLocked("t", 60, () -> "done"));
    }
}
