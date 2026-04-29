package com.kitex.mom.common.flow.config;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * RestTemplateConfig
 *
 * @author kitex
 * @date 2025/2/12 16:02
 */
@Configuration
public class RestTemplateConfig {
    @Bean("restTemplateAdapter")
    @LoadBalanced
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
