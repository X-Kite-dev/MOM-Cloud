package com.kitex.mom.tpm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class DeviceTreeDto {

    @Schema(description = "主键 code")
    private String code;

    @Schema(description = "父code")
    private String parentCode;

    @Schema(description = "名称")
    private String label;

    @Schema(description = "节点类型")
    private String type;

    @Schema(description = "子级")
    private List<DeviceTreeDto> children;

}
