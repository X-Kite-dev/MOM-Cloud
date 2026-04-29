package com.kitex.mom.iot.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * IoT设备连接信息
 *
 * @author system
 * @date 2025-03-12
 */
@Data
@TenantTable
@TableName("iot_device_connection")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "IoT设备连接信息")
public class IotDeviceConnectionEntity extends BaseEntity<IotDeviceConnectionEntity> {

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
     * 连接类型(mqtt/http/coap等)
     */
    @Schema(description = "连接类型")
    private String connectType;

    /**
     * 客户端ID
     */
    @Schema(description = "客户端ID")
    private String clientId;

    /**
     * 连接状态(1-连接中 2-已断开)
     */
    @Schema(description = "连接状态")
    private Integer connectStatus;

    /**
     * 连接时间
     */
    @Schema(description = "连接时间")
    private LocalDateTime connectTime;

    /**
     * 断开时间
     */
    @Schema(description = "断开时间")
    private LocalDateTime disconnectTime;

    /**
     * IP地址
     */
    @Schema(description = "IP地址")
    private String ipAddress;

    /**
     * 工厂编码
     */
    @Schema(description = "工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

    /**
     * SDK版本
     */
    @Schema(description = "SDK版本")
    private String sdkVersion;
} 