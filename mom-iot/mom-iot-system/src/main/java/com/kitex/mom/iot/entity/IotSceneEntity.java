package com.kitex.mom.iot.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.kitex.common.core.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;
import java.util.Map;

/**
 * 场景联动实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "iot_scene", autoResultMap = true)
public class IotSceneEntity extends BaseEntity<IotSceneEntity> {


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
     * 触发条件配置，JSON格式
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> triggerConfig;

    /**
     * 执行动作配置，JSON格式
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Map<String, Object>> actions;
} 