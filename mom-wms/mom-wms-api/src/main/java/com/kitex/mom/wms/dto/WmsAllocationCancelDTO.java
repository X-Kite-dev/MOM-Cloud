package com.kitex.mom.wms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
public class WmsAllocationCancelDTO {
    @Schema(description = "调拨单ID")
    private Long id;

    @Schema(description = "永久幂等请求号")
    private String requestId;

    @Schema(description = "取消原因")
    private String reason;
}
