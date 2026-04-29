package com.kitex.mom.tpm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class DeviceCheckAssignDto {

    @Schema(description = "指派主键集合")
    @NotNull(message = "选择的数据不能为空")
    private List<Long> ids;

    @Schema(description = "指派人编码")
    @NotNull(message = "指派人不能为空")
    private String handlePersonCode;

    @Schema(description = "指派人名称")
    private String handlePersonName;

}
