package com.kitex.mom.common.flow.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
public class OperationDTO {

    /**
     * 原因
     */
    @Schema(description = "原因")
    private String remark;

    /**
     * 类型
     */
    @Schema(description = "类型")
    private String type;
}
