package com.kitex.mom.iot.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 设备元数据查询DTO
 *
 * @author system
 */
@Data
@Schema(description = "设备元数据查询DTO")
public class DeviceMetadataQueryDTO {

    /**
     * 产品ID
     */
    @Schema(description = "产品ID")
    private Long productId;

    /**
     * 物模型标识符
     */
    @Schema(description = "物模型标识符")
    private String modelIdentifier;

    /**
     * 设备ID列表
     */
    @Schema(description = "设备ID列表")
    private List<Long> deviceIds;

    /**
     * 开始时间
     */
    @Schema(description = "开始时间")
    private String startTime;

    /**
     * 结束时间
     */
    @Schema(description = "结束时间")
    private String endTime;
} 