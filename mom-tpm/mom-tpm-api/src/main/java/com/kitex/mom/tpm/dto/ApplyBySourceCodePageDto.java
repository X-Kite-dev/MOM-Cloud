package com.kitex.mom.tpm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ApplyBySourceCodePageDto {

    @NotNull(message = "来源编码不能为空")
    @Schema(description = "来源编码")
    private String sourceCode;

    @NotNull(message = "来源不能为空")
    @Schema(description = "来源 计划更换、故障更换")
    private String source;

    @Schema(description = "备件编码")
    private String sparePartCode;

    @Schema(description = "备件名称")
    private String sparePartName;
}
