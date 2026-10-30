package com.kitex.mom.qms.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 参数异常调查与既有 QMS 不合格品单的受控关联合同。
 * 该回执只证明质量人员接受了关联，不创建 NCR、不变更库存或案件状态。
 */
public final class QmsAgentNcrReferralDTO {

    private QmsAgentNcrReferralDTO() {
    }

    public record Accept(
            @NotBlank @Size(max = 100) String factoryCode,
            @NotBlank @Size(max = 150) String requestId,
            @NotNull Long agentRunId,
            @NotBlank @Size(max = 100) String workOrderCode,
            @NotNull Long ncrId,
            @NotBlank @Size(max = 64) String factFingerprint) {
    }

    public record Receipt(
            String requestId,
            Long tenantId,
            String factoryCode,
            String workOrderCode,
            Long ncrId,
            String ncrNo,
            String sourceType,
            Long sourceTaskId,
            String ncrStatus,
            String operatorCode,
            LocalDateTime acceptedAt,
            String domainReceiptStatus) {
    }

    /**
     * 原 QMS 案件的只读终态证据。closedTime 取最后一条真实明细验证和必需 CAPA 的较晚时间，
     * 不是 Agent 关联时间，也不代表参数恢复或库存放行。
     */
    public record ClosureReceipt(
            String requestId,
            Long tenantId,
            String factoryCode,
            String workOrderCode,
            Long agentRunId,
            Long ncrId,
            String ncrNo,
            String sourceType,
            Long sourceTaskId,
            String ncrStatus,
            LocalDateTime referredAt,
            LocalDateTime closedTime,
            String capaStatus,
            String capaEffectivenessResult,
            boolean lifecycleComplete,
            String factFingerprint,
            List<String> dataGaps,
            List<String> evidenceRefs) {
    }
}
