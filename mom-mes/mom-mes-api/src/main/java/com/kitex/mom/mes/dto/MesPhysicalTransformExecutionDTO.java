package com.kitex.mom.mes.dto;

import lombok.Data;

import java.util.List;

/**
 * 实物转换正式执行命令与永久回执。
 * 执行正文只引用既有命令、来源占用和已确认位置原账，不接受客户端重传库存、工程或计算结果。
 */
public final class MesPhysicalTransformExecutionDTO {

    private MesPhysicalTransformExecutionDTO() {
    }

    /**
     * 正式执行确认；全部身份均按文本传输，服务端只做期望值比对。
     */
    @Data
    public static class Apply {
        /**
         * 固定MES_PHYSICAL_TRANSFORM_APPLY_V1。
         */
        private String version;
        /**
         * 页面观察租户，只与当前实际租户比对。
         */
        private String expectedTenantId;
        /**
         * 页面观察工厂，只与当前factory-code比对。
         */
        private String expectedFactoryCode;
        /**
         * 页面观察本人，只与当前认证用户比对。
         */
        private String expectedActorUserId;
        /**
         * 原永久命令全文SHA。
         */
        private String expectedCommandFingerprint;
        /**
         * 原服务器来源占用全文SHA。
         */
        private String expectedHoldFingerprint;
        /**
         * DEPANEL必须提供已确认位置原账ID；其他动作必须为空串。
         */
        private String positionSurveyId;
        /**
         * DEPANEL必须提供原位置采集全文SHA；其他动作必须为空串。
         */
        private String positionSurveyFingerprint;
        /**
         * 本次不可变执行确认说明。
         */
        private String reason;
    }

    /**
     * 精确投入库存及实际扣减证据。
     */
    @Data
    public static class InputLine {
        private String inputId;
        private String stockId;
        private String sourceReportId;
        private String materialCode;
        private String unit;
        private String quantity;
        private String beforeQuantity;
        private String afterQuantity;
        private String outFlowId;
        private String sourceFingerprint;
    }

    /**
     * 每个输出的实际结果；报废和缺位不伪造可用库存。
     */
    @Data
    public static class OutputLine {
        private String outputId;
        private String outputKey;
        private String identifier;
        private String objectType;
        private String disposition;
        private String positionCode;
        private String quantity;
        private String resultStockId;
        private String inFlowId;
    }

    /**
     * 原投入至输出的不可变当量边。
     */
    @Data
    public static class AllocationLine {
        private String allocationId;
        private String stockId;
        private String outputKey;
        private String quantity;
    }

    /**
     * 永久执行回执；ABSENT不证明请求未到达，必须保留原号核账。
     */
    @Data
    public static class Receipt {
        private String requestId;
        private String executionId;
        private String executionVersion;
        private String commandId;
        private String commandFingerprint;
        private String holdId;
        private String holdFingerprint;
        private String tenantId;
        private String factoryCode;
        private String actorUserId;
        private String actorCode;
        /**
         * APPLIED/ABSENT/UNAVAILABLE。
         */
        private String status;
        private String operationType;
        private String materialCode;
        private String inputUnit;
        private String outputUnit;
        private String inputQuantity;
        private String goodQuantity;
        private String scrapQuantity;
        private String positionSurveyId;
        private String applySnapshot;
        private String applyFingerprint;
        private String calculationSnapshot;
        private String calculationFingerprint;
        private String receiptSnapshot;
        private String receiptFingerprint;
        private String appliedTime;
        private List<InputLine> inputs;
        private List<OutputLine> outputs;
        private List<AllocationLine> allocations;
        private String message;
    }
}
