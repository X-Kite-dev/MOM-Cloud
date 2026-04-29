package com.kitex.mom.iot.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * IoT设备分组关联DTO
 *
 * @author system
 * @date 2025-03-12
 */
@Data
@Schema(description = "IoT设备分组关联DTO")
public class IotDeviceGroupRelationDTO {

    /**
     * 分组ID
     */
    @Schema(description = "分组ID")
    private Long groupId;

    /**
     * 设备ID列表
     */
    @Schema(description = "设备ID列表")
    private List<Long> deviceIds;
} 