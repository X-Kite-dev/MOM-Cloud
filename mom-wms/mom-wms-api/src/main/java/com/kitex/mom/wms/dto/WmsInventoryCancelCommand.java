package com.kitex.mom.wms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 保留盘点历史的作废命令。 */
@Data
public class WmsInventoryCancelCommand {
    @Schema(description = "盘点计划ID")
    private Long id;

    @Schema(description = "客户端稳定请求号，重复提交返回原结果")
    private String requestId;

    @Schema(description = "作废原因，最多500字")
    private String reason;
}
