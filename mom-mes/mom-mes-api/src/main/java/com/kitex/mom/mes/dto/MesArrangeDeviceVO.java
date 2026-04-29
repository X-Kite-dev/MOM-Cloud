package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 设备
 *
 * @author kitex
 * @since 2025-03-01
 */
@Data
public class MesArrangeDeviceVO {

    private String arrangeCode;

    private String taskCode;

    private String produceTaskCode;

    private String deviceCode;

    private String deviceName;

    private String deviceClassCode;

    private String deviceClassName;

    private String deviceLocation;

    private String deviceUsable;

    private String deviceState;

    private Integer deviceStatus;

    private Long deviceId;

    private Long deviceOrgId;

    private String modelCode;

    private String modelName;

    @Schema(description = "关联备件 0 否 1是")
    private String deviceMoldRela;

    private List<MesUsableMoldDTO> moldList;
}
