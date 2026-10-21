package com.kitex.mom.mes.dto;

/**
 * 独立耗料恢复原号回执；所有ID和原文均为文本，不推断库存或生产取消。
 */
public final class MesProductionMaterialReversalDTO {
    private MesProductionMaterialReversalDTO() {
    }

    /**
     * ABSENT只表示该原号无已提交结果，不能据此换号；APPLIED是耗料账恢复。
     */
    public record Receipt(String requestId, String state, String reversalId, String operationId,
                          String tenantId, String factoryCode, String actorId,
                          String commandVersion, String commandSnapshot, String commandFingerprint,
                          String evidenceVersion, String evidenceSnapshot, String evidenceFingerprint) {
    }

    /**
     * 完整原文的只读核账：ABSENT必须保留；NOT_APPLIED_ALREADY_RESTORED表示本号未执行，
     * 但精确原operation已有另一永久恢复账，不能把它展示为本号成功。applied承载真实原账。
     * 外层原文为本次查询的严格回显，不冒充已持久化的业务命令。
     */
    public record Resolution(String requestId, String state, String tenantId, String factoryCode, String actorId,
                             String commandVersion, String commandSnapshot, String commandFingerprint,
                             Receipt applied) {
    }
}
