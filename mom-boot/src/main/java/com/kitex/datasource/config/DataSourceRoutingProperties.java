package com.kitex.datasource.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 数据源路由配置类
 * 用于配置包路径与数据源的映射关系
 */
@Component
@ConfigurationProperties(prefix = "datasource.routing")
public class DataSourceRoutingProperties {

    private List<PackageMapping> packageMappings = new ArrayList<>();

    public List<PackageMapping> getPackageMappings() {
        return packageMappings;
    }

    public void setPackageMappings(List<PackageMapping> packageMappings) {
        this.packageMappings = packageMappings;
    }

    /**
     * 包路径映射配置
     */
    public static class PackageMapping {
        private String packagePath;
        private String datasource;

        public String getPackagePath() {
            return packagePath;
        }

        public void setPackagePath(String packagePath) {
            this.packagePath = packagePath;
        }

        public String getDatasource() {
            return datasource;
        }

        public void setDatasource(String datasource) {
            this.datasource = datasource;
        }
    }
}