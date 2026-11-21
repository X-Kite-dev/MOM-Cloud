package com.kitex.mom.plan.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 智能 APS 正式排程异步下达 MES 的不可变载荷和逐项回执。
 */
public final class PlnApsMesDispatchDTO {

    private PlnApsMesDispatchDTO() {
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "PLAN冻结的MES初始排程下达信封")
    public static class Envelope {
        private String contractVersion;
        private String requestId;
        private String caseId;
        private String scenarioId;
        private String selectedTaskId;
        private String publicationFingerprint;
        private String factoryCode;
        private String mrpAuthority;
        private String planningPolicyId;
        private Integer planningPolicyVersion;
        private String planningPolicyFingerprint;
        private String productionOrderSyncMode;
        private String formalPlanContractNo;
        private Integer formalPlanContractVersion;
        private String formalPlanContractFingerprint;
        private List<PlanItem> items;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "MES初始排程的单个PLAN计划项")
    public static class PlanItem {
        private String dispatchKey;
        private String issueId;
        private String issueNo;
        private List<String> issueIds;
        private List<String> issueNos;
        private String productCode;
        private String productName;
        private String workshopCode;
        private String workshopName;
        private String dispatchEvidenceFingerprint;
        private String operationEvidenceFingerprint;
        private Integer operationCount;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "MES初始排程单项回执")
    public static class ItemReceipt {
        /** 冻结时确定的下达单元键，合批单元可包含多条计划。 */
        private String dispatchKey;
        /** 主来源计划的主键。 */
        private String issueId;
        /** 主来源计划号。 */
        private String issueNo;
        /** 本单元涉及的来源计划主键。 */
        private List<String> issueIds;
        /** 本单元涉及的来源计划号。 */
        private List<String> issueNos;
        /** 单元投递结果，SUCCEEDED 已确认，REJECTED 已拒绝，RETRY_WAIT 待重试。 */
        private String status;
        /** 本次投递的接收说明或失败原因。 */
        private String message;
        /** PLAN 记录本次处理结果的时间。 */
        private LocalDateTime processedTime;

        /** 本单元实际确认的 MES 工单。早期回执及未成功的单元没有此字段。 */
        @Schema(description = "按实际接单列出的工单，拆批可返回多张")
        private List<MesOrderReceipt> mesOrders;
    }

    /** 从 MES 原接单回执及其匹配的冻结载荷取得的一张工单。 */
    @Data
    @Accessors(chain = true)
    @Schema(description = "MES 工单接收明细")
    public static class MesOrderReceipt {
        /** 本工单的永久接单请求号，用于重放核对。 */
        @Schema(description = "MES 接单请求号")
        private String requestId;

        /** 原回执的工单主键，以字符串传输以保留精度。 */
        @Schema(description = "MES 工单主键")
        private String taskId;

        /** MES 首次接单生成的生产工单编码。 */
        @Schema(description = "MES 工单编码")
        private String taskCode;

        /** 该工单所属的 APS 执行批号；普通计划为空。 */
        @Schema(description = "APS 执行批号")
        private String apsBatchNo;

        /** 已确认接单载荷的应产量，按工单物料的数量单位传输十进制原值。 */
        @Schema(description = "工单接单数量")
        private String acceptedQuantity;

        /** MES 首次接收这张工单的时间，重放时沿用原值。 */
        @Schema(description = "MES 首次接单时间")
        private LocalDateTime acceptedTime;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "APS到MES下达队列视图")
    public static class DispatchView {
        private String outboxId;
        private String caseId;
        private String scenarioId;
        private String selectedTaskId;
        private String requestId;
        private String requestFingerprint;
        private String publicationFingerprint;
        private String status;
        private Integer attemptCount;
        private Integer dispatchedCount;
        private Integer failedCount;
        private LocalDateTime nextRetryTime;
        private String lastError;
        private List<ItemReceipt> receipts;
        private LocalDateTime queuedTime;
        private LocalDateTime sentTime;
        private LocalDateTime completedTime;
    }

    @Data
    @Schema(description = "人工重试MES下达命令")
    public static class RetryCommand {
        /** 计划员核对失败原因后填写的处理依据。 */
        @Schema(description = "人工重试依据，最多500字")
        private String reason;
    }

    /** 计划员在执行跟踪中实际查看并确认的一次失败投递。 */
    @Data
    @Schema(description = "按原下达单与失败轮次重新入队")
    public static class ConfirmedRetryCommand {
        /** 原下达单发布时生成的永久 MES 请求号。 */
        @Schema(description = "原MES下达请求号")
        private String requestId;

        /** 打开确认框时的发送次数，防止沿旧观察重试另一轮失败。 */
        @Schema(description = "已核对的发送次数")
        private Integer attemptCount;

        /** 本次人工核对的队列状态，例如 MANUAL_REVIEW。 */
        @Schema(description = "已核对的下达状态")
        private String status;

        /** 本次重新入队的人工依据。 */
        @Schema(description = "人工重试依据，最多500字")
        private String reason;
    }
}
