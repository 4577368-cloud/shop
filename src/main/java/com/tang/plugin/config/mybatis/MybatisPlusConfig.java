package com.tang.plugin.config.mybatis;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.apache.ibatis.reflection.MetaObject;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Configuration
@MapperScan("com.tang.plugin.mapper")
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.H2));
        return interceptor;
    }

    @Component
    public static class MyMetaObjectHandler implements MetaObjectHandler {
        @Override
        public void insertFill(MetaObject metaObject) {
            Instant now = Instant.now();
            strictInsertFill(metaObject, "createTime", Instant.class, now);
            strictInsertFill(metaObject, "updateTime", Instant.class, now);
            strictInsertFill(metaObject, "createdAt", Instant.class, now);
            strictInsertFill(metaObject, "updatedAt", Instant.class, now);
        }

        @Override
        public void updateFill(MetaObject metaObject) {
            Instant now = Instant.now();
            strictUpdateFill(metaObject, "updateTime", Instant.class, now);
            strictUpdateFill(metaObject, "updatedAt", Instant.class, now);
        }
    }
}
