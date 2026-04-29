package com.kitex.mom.common.flow.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * DingTalkPropsConfig
 *
 * @author kitex
 * @date 2025/2/13 17:26
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "ding-talk.event.main")
public class DingTalkPropsConfig {
    /**
     * 事件订阅token
     */
    private String aesToken;
    /**
     * 事件订阅key
     */
    private String aesKey;
    /**
     * 企业的appkey
     */
    private String ownerKey;
    /**
     * 应用agentId
     */
    private Long appAgentId;
}
