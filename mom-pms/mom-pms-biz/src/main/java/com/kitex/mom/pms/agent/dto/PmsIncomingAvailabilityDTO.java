package com.kitex.mom.pms.agent.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 采购收料单进入IQC和WMS前的权威来源快照。
 */
public final class PmsIncomingAvailabilityDTO {

    private PmsIncomingAvailabilityDTO() {
    }

    @Data
    @Accessors(chain = true)
    public static class Query {
        /** 当前实名授权工厂编码，不接受跨厂来源。 */
        @NotBlank
        private String factoryCode;
        /** 精确采购收料单主键，字符串传输。 */
        @NotBlank
        private String receiptId;
    }

    @Data
    @Accessors(chain = true)
    public static class Evidence {
        /** 原领域取证工具版本。 */
        private String toolCode;
        /** 本次数量与状态的业务口径。 */
        private String metricDefinition;
        /** PMS_INCOMING_QUANTITY_V1表示数量保留未知；旧回包缺此标识不得认领数量。 */
        private String quantityEvidenceVersion;
        /** 当前实名授权工厂编码，不接受跨厂来源。 */
        private String factoryCode;
        /** 精确采购收料单主键，字符串传输。 */
        private String receiptId;
        /** 原采购收料单号。 */
        private String receiptNo;
        /** 原采购订单号。 */
        private String purchaseOrderNo;
        /** 原收料供应商编码。 */
        private String supplierCode;
        /** 原收料供应商名称。 */
        private String supplierName;
        /** 原收料目标仓库编码。 */
        private String warehouseCode;
        /** 原收料目标仓库名称。 */
        private String warehouseName;
        /** 原PMS收料状态，4表示完成，不代表WMS可用库存。 */
        private String receiptState;
        /** 本次领域事实读取时间。 */
        private LocalDateTime observedAt;
        /** 同租户收料批次，组织沿原收料主单核验。 */
        private List<BatchLine> batches;
        /** 原批次引用的QMS来料检验任务主键。 */
        private List<String> qualityTaskIds;
        /** 来源或数量缺口，未知量不能据此判断为零。 */
        private List<String> dataGaps;
        /** 本次取证的适用与来源限制。 */
        private List<String> limitations;
        /** 主单与批次原记录证明引用。 */
        private List<String> evidenceRefs;
        /** 含批次、缺口与组织范围的原事实摘要。 */
        private String factFingerprint;
    }

    @Data
    @Accessors(chain = true)
    public static class BatchLine {
        /** 原采购收料批次主键。 */
        private String receiptBatchId;
        /** 原采购订单行主键，可空。 */
        private String purchaseOrderDetailId;
        /** 原订单行采购计划号，可空。 */
        private String purchasePlanNo;
        /** 原采购计划的PLAN月计划来源主键，可空。 */
        private String planSourceIssueMonthId;
        /** 原采购计划的计划/订单引用，可空。 */
        private String planSourceReferenceNo;
        /** 原收料物料编码。 */
        private String materialCode;
        /** 原物料名称。 */
        private String materialName;
        /** 原物料规格。 */
        private String materialSpec;
        /** 原收料物料批次。 */
        private String materialBatch;
        /** 数量的原物料单位，不跨单位汇总。 */
        private String materialUnit;
        /** 原实际收料量，单位见materialUnit；原空值/非有限数保持未知null。 */
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        private BigDecimal receivedQuantity;
        /** 原判定数量，单位见materialUnit；原空值/非有限数保持未知null。 */
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        private BigDecimal inspectedQuantity;
        /** 原PMS回写入库量，单位见materialUnit；未知null与真实0分开。 */
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        private BigDecimal inboundQuantity;
        /** 原批次收料状态，4表示批次完成。 */
        private String receiptLineState;
        /** 本批次原QMS任务主键，免检时可空。 */
        private String qualityTaskId;
        /** 原质量结果，EXEMPTION表示免检，不代替WMS入库。 */
        private String qualityResult;
        /** 原合格入库通知生成标记，可空表示未知。 */
        private Boolean qualifiedNoticeGenerated;
        /** 原不合格通知生成标记，可空表示未知。 */
        private Boolean unqualifiedNoticeGenerated;
    }
}
