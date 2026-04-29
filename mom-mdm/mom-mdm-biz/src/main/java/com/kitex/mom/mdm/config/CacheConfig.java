package com.kitex.mom.mdm.config;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;

/**
 * 缓存配置类
 *
 * @author claude
 * @date 2025-07-30
 */
@Configuration
@EnableCaching
public class CacheConfig {

    /**
     * 配置缓存管理器
     */
    @Bean
    public CacheManager cacheManager() {
        SimpleCacheManager cacheManager = new SimpleCacheManager();

        // 配置不同的缓存区域
        cacheManager.setCaches(Arrays.asList(
                new ConcurrentMapCache("factoryModelNodes"),
                new ConcurrentMapCache("factoryModelCount"),
                new ConcurrentMapCache("factoryModelTypes"),
                new ConcurrentMapCache("factoryModelTree")
        ));

        return cacheManager;
    }
}