package com.kitex.mom.iot.core.network;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 网络组件属性类，用于配置网络组件
 */
@Data
@Builder
public class NetworkProperties {

    /**
     * 网络组件ID
     */
    private String id;

    /**
     * 网络组件名称
     */
    private String name;

    /**
     * 网络组件类型 NetworkType#getId()
     */
    private String type;

    /**
     * 网络组件描述
     */
    private String description;


    /**
     * 配置是否启用
     */
    private boolean enabled;

    /**
     * 配置内容，不同的网络组件，配置内容不同
     */
    private Map<String, Object> properties;

} 