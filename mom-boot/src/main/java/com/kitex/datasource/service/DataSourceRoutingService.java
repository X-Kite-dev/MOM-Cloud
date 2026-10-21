package com.kitex.datasource.service;

import com.kitex.datasource.config.DataSourceRoutingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 数据源路由服务
 * 提供基于配置的包路径到数据源的路由功能
 */
@Service
public class DataSourceRoutingService {

    private static final Logger log = LoggerFactory.getLogger(DataSourceRoutingService.class);

    @Autowired
    private DataSourceRoutingProperties routingProperties;

    private Map<String, String> packageCache = new ConcurrentHashMap<>();

    /**
     * 根据类名确定数据源
     *
     * @param className 完整类名
     * @return 数据源名称，如果未找到匹配则返回null
     */
    public String determineDatasourceByPackage(String className) {
        // 检查缓存
        String cachedResult = packageCache.get(className);
        if (cachedResult != null) {
            return cachedResult;
        }

        // 检查类名是否匹配某个包前缀，最长匹配优先
        String datasource = routingProperties.getPackageMappings().stream()
                .filter(mapping -> className.startsWith(mapping.getPackagePath()))
                .max((m1, m2) -> Integer.compare(m1.getPackagePath().length(), m2.getPackagePath().length()))
                .map(DataSourceRoutingProperties.PackageMapping::getDatasource)
                .orElse(null);

        // 缓存结果
        if (datasource != null) {
            packageCache.put(className, datasource);
            log.debug("Cached datasource mapping: {} -> {}", className, datasource);
        }

        return datasource;
    }

    /**
     * 清除缓存
     */
    public void clearCache() {
        packageCache.clear();
        log.debug("Package datasource cache cleared");
    }

    /**
     * 获取当前配置的所有映射关系
     */
    public List<DataSourceRoutingProperties.PackageMapping> getAllMappings() {
        return routingProperties.getPackageMappings();
    }

    /**
     * 验证配置的有效性
     */
    public void validateConfiguration() {
        List<DataSourceRoutingProperties.PackageMapping> mappings = routingProperties.getPackageMappings();
        if (mappings == null || mappings.isEmpty()) {
            log.warn("No package-to-datasource mappings configured");
            return;
        }

        for (DataSourceRoutingProperties.PackageMapping mapping : mappings) {
            if (mapping.getPackagePath() == null || mapping.getPackagePath().trim().isEmpty()) {
                log.error("Invalid mapping: package path is empty");
                throw new IllegalStateException("Package path cannot be empty in datasource routing configuration");
            }
            if (mapping.getDatasource() == null || mapping.getDatasource().trim().isEmpty()) {
                log.error("Invalid mapping: datasource is empty for package: {}", mapping.getPackagePath());
                throw new IllegalStateException("Datasource cannot be empty for package: " + mapping.getPackagePath());
            }
        }

        log.info("Datasource routing configuration validated successfully with {} mappings", mappings.size());
    }
}