package com.kitex.mom.tpm.dto;

import com.kitex.mom.tpm.entity.TpmCheckTaskDetailsEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
public class DeviceCheckTaskStartDto {


    @Schema(description = "设备编码")
    private String deviceCode;

    @Schema(description = "设备 - 任务明细")
    private List<TpmCheckTaskDetailsEntity> deviceDetailsList;


    @Schema(description = "模具编码")
    private String moldCode;


    @Schema(description = "模具 - 任务明细")
    private List<TpmCheckTaskDetailsEntity> moldDetailsList;

    @Schema(description = "换模")
    private Boolean changeMold;

    @Schema(description = "工作中心")
    private String worksiteCode;
}
