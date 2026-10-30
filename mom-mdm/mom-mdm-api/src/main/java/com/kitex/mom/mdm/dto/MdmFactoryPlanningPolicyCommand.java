package com.kitex.mom.mdm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工厂计划协同策略受控命令。
 */
public final class MdmFactoryPlanningPolicyCommand {
    private MdmFactoryPlanningPolicyCommand() {
    }

    /**
     * 创建下一版草稿；工厂身份和版本号由服务端确定。
     */
    @Data
    public static class CreateDraft {
        private String factoryModelId;
        private String mrpAuthority;
        private String erpSystemCode;
        private String externalFactoryCode;
        private String productionOrderSyncMode;
        private String substituteGovernanceMode;
        private LocalDateTime effectiveFrom;
        @Schema(description = "业务变更原因")
        private String changeReason;
        @Schema(description = "永久幂等请求号")
        private String requestId;
    }

    /**
     * 送审后执行异岗发布或预约生效。
     */
    @Data
    public static class Publish {
        private String draftId;
        private String expectedFingerprint;
        private String impactConfirmationNo;
        @Schema(description = "首次独立MOM启用，仅作用于新合同并保留历史单据")
        private Boolean initialLocalActivation;
        private String requestId;
    }
}
