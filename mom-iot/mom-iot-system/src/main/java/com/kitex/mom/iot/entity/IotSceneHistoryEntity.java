package com.kitex.mom.iot.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.kitex.common.core.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

/**
 * 场景联动执行历史实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "iot_scene_history", autoResultMap = true)
public class IotSceneHistoryEntity extends BaseEntity<IotSceneHistoryEntity> {


    /**
     * 场景名称
     */
    private String sceneName;

    /**
     * 触发类型
     */
    private String triggerType;

    /**
     * 执行状态：0-失败，1-成功
     */
    private Integer status;

    /**
     * 执行结果，JSON格式
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> result;

    /**
     * 错误信息
     */
    private String errorMessage;
} 