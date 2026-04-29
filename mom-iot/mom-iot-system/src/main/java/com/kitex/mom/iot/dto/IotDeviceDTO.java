package com.kitex.mom.iot.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * IoT设备DTO
 *
 * @author system
 * @date 2025-03-12
 */
@Data
@Schema(description = "IoT设备DTO")
public class IotDeviceDTO {

    /**
     * ID
     */
    @Schema(description = "ID")
    private Long id;

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
    private String factoryCode;


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
    @Schema(description = "设备类型名称")
    private String deviceTypeName;
} 