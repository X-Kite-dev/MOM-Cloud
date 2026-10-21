package com.kitex.mom.oms.dto;

import com.kitex.mom.oms.entity.OmsOrderSupplyReplanEntity;
import com.kitex.mom.oms.entity.OmsOrderSupplyReplanLineEntity;
import com.kitex.mom.oms.entity.OmsOrderSupplyReplanOperationEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

/** OMS订单变更触发的已下达供应撤回、MRP重算和最终评审命令。 */
public final class OmsOrderSupplyReplanDTO {
    private OmsOrderSupplyReplanDTO() {
    }

    @Data
    public static class Query {
        @Schema(description = "订单号")
        private String orderCode;
        @Schema(description = "编排状态")
        private String status;
        @Schema(description = "工厂编码；只作为筛选值，服务端仍以当前工厂上下文授权")
        private String factoryCode;
    }

    @Data
    public static class SubmitCommand {
        @Schema(description = "OMS订单变更评审ID", requiredMode = Schema.RequiredMode.REQUIRED)
        private Long reviewId;
        @Schema(description = "提交幂等号", requiredMode = Schema.RequiredMode.REQUIRED)
        private String requestId;
        @Schema(description = "级联撤回和重算原因", requiredMode = Schema.RequiredMode.REQUIRED)
        private String reason;
    }

    @Data
    public static class ReviewCommand {
        @Schema(description = "级联重排申请ID", requiredMode = Schema.RequiredMode.REQUIRED)
        private Long replanId;
        @Schema(description = "复核幂等号", requiredMode = Schema.RequiredMode.REQUIRED)
        private String requestId;
        @Schema(description = "申请时冻结的综合证据指纹", requiredMode = Schema.RequiredMode.REQUIRED)
        private String expectedEvidenceFingerprint;
        @Schema(description = "是否批准", requiredMode = Schema.RequiredMode.REQUIRED)
        private Boolean approved;
        @Schema(description = "复核意见", requiredMode = Schema.RequiredMode.REQUIRED)
        private String reason;
    }

    @Data
    public static class RetryCommand {
        @Schema(description = "级联重排申请ID", requiredMode = Schema.RequiredMode.REQUIRED)
        private Long replanId;
        @Schema(description = "重试幂等号", requiredMode = Schema.RequiredMode.REQUIRED)
        private String requestId;
        @Schema(description = "重试原因", requiredMode = Schema.RequiredMode.REQUIRED)
        private String reason;
    }

    @Data
    @Accessors(chain = true)
    public static class Detail {
        private OmsOrderSupplyReplanEntity replan;
        private List<OmsOrderSupplyReplanLineEntity> lines;
        private List<OmsOrderSupplyReplanOperationEntity> operations;
    }
}
