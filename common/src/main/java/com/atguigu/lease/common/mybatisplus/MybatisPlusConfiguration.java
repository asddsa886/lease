package com.atguigu.lease.common.mybatisplus;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan({
        // web-admin / web-app 各自的 mapper（不同应用启动时，只会扫描到 classpath 上存在的包）
        "com.atguigu.lease.web.admin.mapper",
        "com.atguigu.lease.web.app.mapper",

        // Outbox（本地消息表）
        "com.atguigu.lease.common.mq.outbox.mapper"
})
public class MybatisPlusConfiguration {
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
