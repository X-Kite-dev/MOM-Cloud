package com.kitex.datasource.config;

import com.kitex.datasource.service.DataSourceRoutingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * 数据源路由配置验证组件
 * 在应用启动时验证数据源路由配置的有效性
 */
@Component
public class DataSourceRoutingValidator implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSourceRoutingValidator.class);

    @Autowired
    private DataSourceRoutingService routingService;

    @Override
    public void run(String... args) throws Exception {
        log.info("Validating datasource routing configuration...");

        try {
            routingService.validateConfiguration();
            log.info("Datasource routing configuration validation completed successfully");
        } catch (Exception e) {
            log.error("Datasource routing configuration validation failed", e);
            throw e;
        }
    }
}