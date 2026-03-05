package com.tencent.supersonic.common.config;

import javax.sql.DataSource;

import com.alibaba.druid.pool.DruidDataSource;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
@Primary
public class DataBaseConfig {

    @Bean("h2")
    @Primary
    @ConfigurationProperties("spring.datasource")
    public DataSource dataSource() {
        DruidDataSource druidDataSource = new DruidDataSource();
        // 防止 MySQL wait_timeout 导致的“连接被服务端断开但连接池仍复用”的问题。
        druidDataSource.setValidationQuery("select 1");
        druidDataSource.setValidationQueryTimeout(5);
        druidDataSource.setTestWhileIdle(true);

        // 定期检测/回收空闲连接（低频访问场景下尤其重要）
        druidDataSource.setTimeBetweenEvictionRunsMillis(60_000);
        druidDataSource.setMinEvictableIdleTimeMillis(5 * 60_000);
        druidDataSource.setMaxEvictableIdleTimeMillis(15 * 60_000);

        // 借出连接前做一次校验，最稳（会多一次轻量 query）
        druidDataSource.setTestOnBorrow(true);
        druidDataSource.setTestOnReturn(false);

        return druidDataSource;
    }
}
