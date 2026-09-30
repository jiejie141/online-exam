package com.jiejie.exam;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 启动类。
 *
 * <p>这里用 Spring MVC（spring-boot-starter-web）而不是 WebFlux：
 * 考试系统里"交卷 + 判分"是一个必须整体成功或整体失败的操作，要用 @Transactional；
 * 而事务和 ThreadLocal 绑定在线程上，WebFlux 的事件循环模型里用不了。
 * 业务系统要的是阻塞式 JDBC + 声明式事务，反应式在这里只会增加复杂度。
 */
@EnableScheduling
@MapperScan("com.jiejie.exam.domain.mapper")
@SpringBootApplication
public class OnlineExamApplication {

    public static void main(String[] args) {
        SpringApplication.run(OnlineExamApplication.class, args);
    }
}
