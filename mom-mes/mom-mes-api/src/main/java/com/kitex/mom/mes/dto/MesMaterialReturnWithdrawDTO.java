package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 原申请人撤回待审核生产退料申请。
 */
@Data
@Schema(description = "生产退料申请撤回命令")
public class MesMaterialReturnWithdrawDTO {
    @Schema(description = "退料申请主键", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long id;
    @Schema(description = "撤回永久幂等请求号", requiredMode = Schema.RequiredMode.REQUIRED)
    private String requestId;
    @Schema(description = "撤回原因，1至500字", requiredMode = Schema.RequiredMode.REQUIRED)
    private String reason;
}
