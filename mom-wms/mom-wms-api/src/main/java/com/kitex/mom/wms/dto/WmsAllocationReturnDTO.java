package com.kitex.mom.wms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class WmsAllocationReturnDTO {
    @Schema(description = "待退回调拨单ID")
    private Long id;

    @Schema(description = "退回确认永久幂等请求号")
    private String requestId;

    @Schema(description = "实物已回到源仓原库位的确认凭证")
    private String evidence;

    @Schema(description = "逐条发出流水核对的退回数量")
    private List<Line> lines;

    @Data
    public static class Line {
        private Long sourceFlowId;
        private BigDecimal returnedQuantity;
    }
}
