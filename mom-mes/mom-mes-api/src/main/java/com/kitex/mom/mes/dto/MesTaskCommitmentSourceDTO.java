package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

/**
 * 现场承诺摘要的精确物理工单来源，只读，不接受客户端指定计划范围。
 */
public final class MesTaskCommitmentSourceDTO {
    private MesTaskCommitmentSourceDTO() {
    }

    @Data
    @Accessors(chain = true)
    public static class Evidence {
        @Schema(description = "实际租户ID文本")
        private String tenantId;
        @Schema(description = "实际工厂")
        private String factoryCode;
        @Schema(description = "真实物理MES工单ID文本")
        private String taskId;
        @Schema(description = "真实物理MES工单编码")
        private String taskCode;
        @Schema(description = "原产品编码")
        private String productCode;
        @Schema(description = "物理工单原下达量文本，不作为各来源完成量")
        private String physicalQuantity;
        @Schema(description = "完整直接/APS来源；超限拒绝，不截断")
        private List<Source> sources;
    }

    @Data
    @Accessors(chain = true)
    public static class Source {
        @Schema(description = "DIRECT_PLAN或APS_BATCH_ALLOCATION")
        private String relationType;
        @Schema(description = "APS来源行ID文本，直接来源为空")
        private String allocationId;
        @Schema(description = "APS冻结来源计划ID文本，直接来源在PLAN精确核验")
        private String issueId;
        @Schema(description = "原来源生产计划号")
        private String issueNo;
        @Schema(description = "原分配给此物理工单的来源量文本")
        private String allocatedQuantity;
    }
}
