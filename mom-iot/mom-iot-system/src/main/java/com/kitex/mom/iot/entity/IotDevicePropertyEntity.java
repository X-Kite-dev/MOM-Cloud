package com.kitex.mom.iot.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseCore;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * IoT设备属性数据
 *
 * @author system
 * @date 2025-03-12
 */
@Data
@TableName("iot_device_property")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "IoT设备属性数据")
public class IotDevicePropertyEntity extends BaseCore<IotDevicePropertyEntity> {


    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "主键id")
    private Long id;
    /**
     * 设备ID
     */
    @Schema(description = "设备ID")
    private Long deviceId;

    /**
     * 设备编码
     */
    @Schema(description = "设备编码")
    private String deviceCode;

    /**
     * 产品ID
     */
    @Schema(description = "产品ID")
    private Long productId;

    /**
     * 产品编码
     */
    @Schema(description = "产品编码")
    private String productCode;

    /**
     * 属性标识符
     */
    @Schema(description = "属性标识符")
    private String identifier;

    /**
     * 属性名称
     */
    @Schema(description = "属性名称")
    private String name;

    /**
     * 属性值
     */
    @Schema(description = "属性值")
    private String value;

    /**
     * 数据类型
     */
    @Schema(description = "数据类型")
    private String dataType;

    /**
     * 单位
     */
    @Schema(description = "单位")
    private String unit;

    /**
     * 采集时间
     */
    @Schema(description = "采集时间")
    private LocalDateTime collectTime;
} 