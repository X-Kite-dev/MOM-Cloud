package com.kitex.mom.mes.dto;

import com.kitex.mom.mes.entity.MesDispatchReturnOperationEntity;
import com.kitex.mom.mes.entity.MesDispatchReturnRequestEntity;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.entity.MesToolingExecutionEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

/**
 * 已接收未开工派工的申请、审批和证据查看契约。
 */
public final class MesDispatchReturnDTO {
    private MesDispatchReturnDTO() {
    }

    @Data
    @Schema(description = "提交已接收未开工退派申请")
    public static class SubmitCommand {
        @Schema(description = "派工主键", requiredMode = Schema.RequiredMode.REQUIRED)
        private Long arrangeId;
        @Schema(description = "16至64位永久幂等请求号", requiredMode = Schema.RequiredMode.REQUIRED)
        private String requestId;
        @Schema(description = "预览取得的64位事实指纹", requiredMode = Schema.RequiredMode.REQUIRED)
        private String expectedEvidenceFingerprint;
        @Schema(description = "退派原因，1至500字", requiredMode = Schema.RequiredMode.REQUIRED)
        private String reason;
    }

    @Data
    @Schema(description = "异岗审核退派申请")
    public static class ReviewCommand {
        @Schema(description = "退派申请主键", requiredMode = Schema.RequiredMode.REQUIRED)
        private Long requestId;
        @Schema(description = "本次审批永久幂等请求号", requiredMode = Schema.RequiredMode.REQUIRED)
        private String operationRequestId;
        @Schema(description = "申请冻结的64位事实指纹", requiredMode = Schema.RequiredMode.REQUIRED)
        private String expectedEvidenceFingerprint;
        @Schema(description = "true批准，false驳回", requiredMode = Schema.RequiredMode.REQUIRED)
        private Boolean approved;
        @Schema(description = "审核依据，1至500字", requiredMode = Schema.RequiredMode.REQUIRED)
        private String reason;
    }

    @Data
    @Schema(description = "原申请人撤回待审核退派申请")
    public static class WithdrawCommand {
        @Schema(description = "退派申请主键", requiredMode = Schema.RequiredMode.REQUIRED)
        private Long requestId;
        @Schema(description = "本次撤回永久幂等请求号", requiredMode = Schema.RequiredMode.REQUIRED)
        private String operationRequestId;
        @Schema(description = "撤回原因，1至500字", requiredMode = Schema.RequiredMode.REQUIRED)
        private String reason;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "退派资格及当前事实证据")
    public static class Evidence {
        private MesTaskArrangeEntity arrange;
        private String taskStatus;
        private String produceTaskStatus;
        private List<MesToolingExecutionEntity> toolingExecutions;
        private String evidenceFingerprint;
        private Boolean eligible;
        private List<String> blockers;
        private MesDispatchReturnRequestEntity activeRequest;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "退派申请详情、当前事实和不可变履历")
    public static class Detail {
        private MesDispatchReturnRequestEntity request;
        private Evidence currentEvidence;
        private List<MesDispatchReturnOperationEntity> operations;
    }
}
