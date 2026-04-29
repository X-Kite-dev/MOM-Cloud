package com.kitex.mom.iot.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * IoT设备分组DTO
 *
 * @author system
 * @date 2025-03-12
 */
@Data
@Schema(description = "IoT设备分组DTO")
public class IotDeviceGroupDTO {

    /**
     * ID
     */
    @Schema(description = "ID")
    private Long id;

    /**
     * 分组名称
     */
    @Schema(description = "分组名称")
    private String groupName;

    /**
     * 分组编码
     */
    @Schema(description = "分组编码")
    private String groupCode;

    /**
     * 父分组ID
     */
    @Schema(description = "父分组ID")
    private Long parentId;

    /**
     * 分组描述
     */
    @Schema(description = "分组描述")
    private String description;

    /**
     * 排序
     */
    @Schema(description = "排序")
    private Integer sort;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    private String factoryCode;
} 