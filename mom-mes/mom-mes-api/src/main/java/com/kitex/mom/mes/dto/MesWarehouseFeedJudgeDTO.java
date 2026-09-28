package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 线边库余料退料审批提交DTO
 */
@Data
@Schema(description = "生产余料退库异岗审核命令")
public class MesWarehouseFeedJudgeDTO {

    @Schema(description = "退料申请主键", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long id;

    @Schema(description = "审核依据，1至500字", requiredMode = Schema.RequiredMode.REQUIRED)
    private String opinion;

    @Schema(description = "true批准并通知WMS，false驳回", requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean pass;

    @Schema(description = "审批永久幂等请求号；失败结果不明确时必须原号重试", requiredMode = Schema.RequiredMode.REQUIRED)
    private String requestId;

    @Schema(description = "申请冻结的来源库存事实指纹", requiredMode = Schema.RequiredMode.REQUIRED)
    private String expectedEvidenceFingerprint;
}
