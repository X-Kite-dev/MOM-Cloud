package com.kitex.mom.iot.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 场景联动数据传输对象
 */
@Data
public class IotSceneDTO {

    /**
     * 场景ID
     */
    private Long id;

    /**
     * 场景名称
     */
    private String name;

    /**
     * 场景类型：device-设备触发，scheduled-定时触发，manual-手动触发
     */
    private String triggerType;

    /**
     * 场景描述
     */
    private String description;

    /**
     * 场景状态：0-禁用，1-启用
     */
    private Integer status;

    /**
     * 触发条件配置
     */
    private Map<String, Object> triggerConfig;

    /**
     * 执行动作配置
     */
    private List<Map<String, Object>> actions;

    /**
     * 流程设计配置
     */
    private Map<String, Object> flowConfig;
} 