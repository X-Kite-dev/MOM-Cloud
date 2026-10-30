package com.kitex.mom.plan.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

/**
 * MES/PDA/工站按真实工单查询的受限只读摘要，不输出审批账或全量计划。
 */
public final class PlnMpsRemainingFieldDTO {
    private PlnMpsRemainingFieldDTO() {
    }

    @Data
    @Accessors(chain = true)
    public static class Query {
        @Schema(description = "实际组织下真实物理工单编码；来源范围由MES反查")
        private String taskCode;
    }

    @Data
    @Accessors(chain = true)
    public static class Summary {
        @Schema(description = "实际租户ID文本")
        private String tenantId;
        @Schema(description = "实际工厂")
        private String factoryCode;
        @Schema(description = "真实物理工单编码")
        private String taskCode;
        @Schema(description = "采集时刻ISO文本；摘要不是发布或报工授权")
        private String observedAt;
        @Schema(description = "完整逐来源摘要，未核验项明确标识，不填零")
        private List<Row> sources = new ArrayList<>();
    }

    @Data
    @Accessors(chain = true)
    public static class Row {
        @Schema(description = "来源生产计划号")
        private String issueNo;
        @Schema(description = "直接/APS来源类型")
        private String relationType;
        @Schema(description = "APS来源分配行ID文本，直接来源为空")
        private String allocationId;
        @Schema(description = "分配至当前物理工单的来源量文本")
        private String taskAllocatedQuantity;
        @Schema(description = "当前物理工单对此来源的有效贡献文本；未知为空")
        private String taskReportedQuantity;
        @Schema(description = "原MPS桶ID文本")
        private String lineId;
        @Schema(description = "产品权威单位")
        private String productUnit;
        @Schema(description = "原MPS来源责任量Q，不是本物理工单量")
        private String originalQuantity;
        @Schema(description = "当前发布版本的冻结基线B，无新分期为空")
        private String baselineQuantity;
        @Schema(description = "当前发布版本原始R，无新分期为空")
        private String committedRemainingQuantity;
        @Schema(description = "全部物理来源对此原桶的当前有效报工C；未知为空")
        private String currentQuantity;
        @Schema(description = "当前未兑现来源责任量；未知为空")
        private String remainingQuantity;
        @Schema(description = "原内部计划日，始终保留")
        private String originalPlannedDate;
        @Schema(description = "原客户交期，不以内部重承诺覆盖")
        private String customerDueDate;
        @Schema(description = "当前生效剩余承诺业务号，未发布为空")
        private String commitmentNo;
        @Schema(description = "当前生效版本号，待审不展示为正式版本")
        private Integer commitmentVersion;
        @Schema(description = "NOT_MPS/ORIGINAL/ACTIVE/FULFILLED/BASELINE_STALE/SOURCE_STALE/BACKFILLED_BASELINE/DATA_GAP")
        private String result;
        @Schema(description = "完整内部日桶数量与时间口径；生产完成不等于入库或质量净放行")
        private List<PlnMpsRemainingCommitmentDTO.Fulfillment> buckets = new ArrayList<>();
        @Schema(description = "来源、数量、时效等待核项，禁止未知按零处理")
        private List<String> gaps = new ArrayList<>();
    }
}
