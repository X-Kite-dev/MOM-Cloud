package com.kitex.mom.tpm.dto;

import com.kitex.mom.tpm.entity.TpmCheckTaskDetailsEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

@Data
@Accessors(chain = true)
public class DeviceChangeMoldDto {


    @Schema(description = "旧模具编码")
    private String oldMoldCode;


    @Schema(description = "新模具编码")
    private String newMoldCode;


    @Schema(description = "新模具 - 任务明细")
    private List<TpmCheckTaskDetailsEntity> newMoldDetailsList;


}
