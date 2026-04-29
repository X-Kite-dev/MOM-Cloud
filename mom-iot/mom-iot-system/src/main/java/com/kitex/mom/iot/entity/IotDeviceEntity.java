package com.kitex.mom.iot.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import com.kitex.mom.iot.enums.DeviceTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * IoT设备
 *
 * @author system
 * @date 2025-03-12
 */
@Data
@TenantTable
@TableName("iot_device")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "IoT设备")
public class IotDeviceEntity extends BaseEntity<IotDeviceEntity> {

    /**
     * 设备编码
     */
    @Schema(description = "设备编码")
    private String deviceCode;

    /**
     * 设备名称
     */
    @Schema(description = "设备名称")
    private String deviceName;

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
     * 产品名称
     */
    @Schema(description = "产品名称")
    private String productName;

    /**
     * 设备状态(1-未激活 2-在线 3-离线 4-禁用)
     */
    @Schema(description = "设备状态")
    private Integer status;


    /**
     * 设备秘钥
     */
    @Schema(description = "设备秘钥")
    private String deviceSecret;

    /**
     * 激活时间
     */
    @Schema(description = "激活时间")
    private LocalDateTime activeTime;

    /**
     * 最后上线时间
     */
    @Schema(description = "最后上线时间")
    private LocalDateTime lastOnlineTime;

    /**
     * 固件版本
     */
    @Schema(description = "固件版本")
    private String firmwareVersion;

    /**
     * 所在区域
     */
    @Schema(description = "所在区域")
    private String region;

    /**
     * 位置信息
     */
    @Schema(description = "位置信息")
    private String location;

    /**
     * IP地址
     */
    @Schema(description = "IP地址")
    private String ipAddress;

    /**
     * 标签(JSON格式)
     */
    @Schema(description = "标签")
    private String tags;

    /**
     * 备注
     */
    @Schema(description = "备注")
    private String remark;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

    /**
     * 产品信息(非数据库字段)
     */
    @TableField(exist = false)
    @Schema(description = "产品信息")
    private IotProductEntity product;

    /**
     * 设备类型（0-直连设备 1-网关设备 2-网关子设备）
     */
    @Schema(description = "设备类型")
    private String deviceType;

    /**
     * 网关设备ID（仅子设备需要）
     */
    @Schema(description = "网关设备ID")
    private Long gatewayDeviceId;

    /**
     * 设备类型名称（非数据库字段）
     */
    @TableField(exist = false)
    @Schema(description = "设备类型名称")
    private String deviceTypeName;

    public String getDeviceTypeName() {
        return DeviceTypeEnum.getLabelByValue(this.deviceType);
    }
} 