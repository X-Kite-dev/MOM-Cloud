package com.kitex.datasource.controller;

import com.kitex.datasource.config.DataSourceRoutingProperties;
import com.kitex.datasource.service.DataSourceRoutingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 数据源路由管理控制器
 * 提供数据源路由配置的管理接口
 */
@RestController
@RequestMapping("/api/datasource-routing")
public class DataSourceRoutingController {

    private static final Logger log = LoggerFactory.getLogger(DataSourceRoutingController.class);

    @Autowired
    private DataSourceRoutingService routingService;

    @Autowired
    private DataSourceRoutingProperties routingProperties;

    /**
     * 获取当前所有包路径映射配置
     */
    @GetMapping("/mappings")
    public List<DataSourceRoutingProperties.PackageMapping> getMappings() {
        return routingService.getAllMappings();
    }

    /**
     * 清除缓存
     */
    @PostMapping("/cache/clear")
    public String clearCache() {
        routingService.clearCache();
        return "Cache cleared successfully";
    }

    /**
     * 重新验证配置
     */
    @PostMapping("/validate")
    public String validateConfiguration() {
        routingService.validateConfiguration();
        return "Configuration validation completed";
    }

    /**
     * 测试类名路由
     */
    @GetMapping("/test")
    public String testRouting(@RequestParam String className) {
        String datasource = routingService.determineDatasourceByPackage(className);
        return String.format("Class '%s' routes to datasource: %s", className, datasource);
    }
}