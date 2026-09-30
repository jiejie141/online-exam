package com.jiejie.exam.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jiejie.exam.domain.dto.PaperItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaperCacheTest {

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> ops;

    private PaperCache cache;

    @BeforeEach
    void setUp() {
        // 这里不 stub opsForValue()：纯函数那几个测试根本不碰 Redis，
        // 在 setUp 里统一 stub 会被 Mockito 的严格模式判成 UnnecessaryStubbing
        cache = new PaperCache(redis, new ObjectMapper(), 600, 60, 0.2);
    }

    /** 需要走 Redis 的测试显式调用这个，把 opsForValue() 接上 */
    private void withRedis() {
        when(redis.opsForValue()).thenReturn(ops);
    }

    private static PaperItem item(long id, String stem) {
        PaperItem item = new PaperItem();
        item.setId(id);
        item.setStem(stem);
        item.setType("single");
        item.setScore(5);
        item.setSeq((int) id);
        return item;
    }

    // ---------------- TTL 抖动（防雪崩）----------------

    @Test
    void 抖动的取值范围是base的正负ratio之间() {
        int base = 600;
        int span = 120;
        // random01 的取值范围是 [0,1)，所以循环到 99/100
        for (int i = 0; i < 100; i++) {
            long ttl = PaperCache.jitteredTtl(base, 0.2, i / 100.0);
            assertTrue(ttl >= base - span, "TTL 不该低于下界: " + ttl);
            assertTrue(ttl <= base + span, "TTL 不该高于上界: " + ttl);
        }
    }

    @Test
    void 随机数取两端时正好落在边界上() {
        assertEquals(480, PaperCache.jitteredTtl(600, 0.2, 0.0));
        assertEquals(720, PaperCache.jitteredTtl(600, 0.2, 0.999));
    }

    @Test
    void 抖动比例为0时TTL就是base() {
        assertEquals(600, PaperCache.jitteredTtl(600, 0.0, 0.37));
    }

    // ---------------- 空值哨兵（防穿透）----------------

    @Test
    void 缓存里是哨兵值表示这门考试确实没题() {
        withRedis();
        when(ops.get("exam:paper:1")).thenReturn(PaperCache.NULL_SENTINEL);

        assertEquals(List.of(), cache.get(1L));
    }

    @Test
    void 空卷子写入缓存时存的是哨兵而不是空串() {
        withRedis();

        cache.put(1L, List.of());

        verify(ops).set("exam:paper:1", PaperCache.NULL_SENTINEL, 60, TimeUnit.SECONDS);
    }

    @Test
    void 没缓存过返回null交给调用方去查库() {
        withRedis();
        when(ops.get("exam:paper:1")).thenReturn(null);

        assertNull(cache.get(1L));
    }

    @Test
    void 正常卷子写缓存时TTL带抖动() {
        withRedis();

        cache.put(2L, List.of(item(1L, "1+1=?")));

        verify(ops).set(eq("exam:paper:2"), any(), anyLong(), eq(TimeUnit.SECONDS));
    }

    @Test
    void 缓存里是坏数据时删掉并返回null触发重建() {
        withRedis();
        when(ops.get("exam:paper:3")).thenReturn("{not-json");

        assertNull(cache.get(3L));
        verify(redis).delete("exam:paper:3");
    }

    // ---------------- 重建锁（防击穿）----------------

    @Test
    void 抢到锁返回token没抢到返回null() {
        withRedis();
        when(ops.setIfAbsent(eq("exam:paper:lock:1"), any(), any())).thenReturn(true);

        assertNotNull(cache.tryLock(1L, 10));
    }

    @Test
    void 没抢到锁时返回null不阻塞() {
        withRedis();
        when(ops.setIfAbsent(eq("exam:paper:lock:1"), any(), any())).thenReturn(false);

        assertNull(cache.tryLock(1L, 10));
    }

    @Test
    void 释放锁走Lua保证只删自己那把锁() {
        cache.unlock(1L, "token");

        verify(redis).execute(any(), eq(List.of("exam:paper:lock:1")), eq("token"));
    }

    // ---------------- Redis 挂掉时的降级 ----------------

    @Test
    void Redis读失败时返回null而不是把异常抛给正在考试的学生() {
        withRedis();
        when(ops.get("exam:paper:1")).thenThrow(new RuntimeException("connection refused"));

        assertNull(cache.get(1L));
    }

    @Test
    void Redis写失败时静默跳过() {
        withRedis();
        // set 返回 void，Mockito 对无返回值方法要用 doThrow(...).when(...) 的写法
        org.mockito.Mockito.doThrow(new RuntimeException("connection refused"))
                .when(ops).set(eq("exam:paper:2"), any(), anyLong(), eq(TimeUnit.SECONDS));

        cache.put(2L, List.of(item(1L, "1+1=?")));
    }

    @Test
    void Redis挂掉时抢锁返回null由调用方直接查库() {
        withRedis();
        when(ops.setIfAbsent(eq("exam:paper:lock:1"), any(), any()))
                .thenThrow(new RuntimeException("connection refused"));

        assertNull(cache.tryLock(1L, 10));
    }
}
