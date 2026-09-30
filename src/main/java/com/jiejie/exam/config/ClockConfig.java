package com.jiejie.exam.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 把"现在几点"做成 Bean，而不是在业务代码里直接 LocalDateTime.now()。
 *
 * <p>原因很实际：考试系统里"有没有超时"是核心判断，
 * 直接调 now() 的代码没法测 —— 你没法让测试等到考试结束。
 * 注入 Clock 之后，测试里换成 Clock.fixed() 就能构造任意时刻。
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
