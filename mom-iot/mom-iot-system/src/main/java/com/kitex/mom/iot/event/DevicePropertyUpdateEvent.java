package com.kitex.mom.iot.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 设备属性更新事件
 * 用于解耦设备属性服务和设备服务
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DevicePropertyUpdateEvent {

    /**
     * 产品ID
     */
    private String productId;

    /**
     * 设备ID
     */
    private String deviceId;

    /**
     * 设备属性数据
     */
    private Map<String, Object> properties;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;
} 