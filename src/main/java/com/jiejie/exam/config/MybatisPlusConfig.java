package com.jiejie.exam.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 插件。目前只有一个分页插件。
 *
 * <p>为什么必须有它：{@code selectPage} 在没有分页插件时<b>不会报错</b>，
 * 它只是把 {@code LIMIT} 丢掉、把整张表捞出来再在内存里截一段。
 * 于是「分页」看起来是好的（返回条数也对），而数据库每次都在做全表扫 ——
 * 直到数据量上来才突然变慢，那时已经很难联想到是插件没装。
 *
 * <p>为什么不写在 OnlineExamApplication 上：分页是数据访问层的策略，
 * 和「这是个 Spring Boot 应用」没有关系。放在 config 包里，
 * 以后要加乐观锁、防全表更新之类的插件，也有一个明确的落点。
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        // 单页上限。没有上限的分页等于对外开了一个「一次拉全表」的开关：
        // 前端传 size=100000，数据库和 JVM 就一起疼。
        // 服务层还会再夹一次（见 TeacherController.clampSize），这里是最后一道。
        pagination.setMaxLimit(100L);
        // 页码超出总页数时返回空列表，而不是悄悄回到第一页。
        // 回到第一页会让「翻到第 5 页」看起来成功了，实际显示的是第 1 页的数据 ——
        // 用户以为自己在第 5 页，数据对不上时根本无从判断是分页错了还是数据错了。
        pagination.setOverflow(false);
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }
}
