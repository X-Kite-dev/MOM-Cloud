package com.kitex.mom.tpm.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 单条维修任务的受控取证合同。维修闭环与设备可用是不同结论，不能相互替代。
 */
public final class TpmRepairInvestigationDTO {

    private TpmRepairInvestigationDTO() {
    }

    @Data
    @Accessors(chain = true)
    public static class Query {
        @NotBlank
        private String factoryCode;
        @NotBlank
        private String repairTaskId;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "单条维修任务、异人验证及恢复资格阻断的权威事实")
    public static class Evidence {
        private String toolCode;
        private String factoryCode;
        private String repairTaskId;
        private LocalDateTime observedAt;
        private String factFingerprint;
        private RepairTask task;
        private Verification verification;
        private Boolean repairClosureVerified;
        private Boolean deviceAvailabilityVerified;
        private Boolean deviceHoldRecorded;
        private Integer affectedScheduleSegmentCount;
        /** 已验证维修版本中的登记用量；须与 TPM 备件库存回执核对后才能认定净用量。 */
        private Boolean verifiedPartsAvailable;
        private List<VerifiedPart> verifiedParts = new ArrayList<>();
        /** 原TPM备件审批事务生成的出库/退回回执与维修登记净用量对账。 */
        private SpareStockReconciliation spareStockReconciliation;
        private List<String> recoveryBlockers = new ArrayList<>();
        private List<String> limitations = new ArrayList<>();
        private List<String> dataGaps = new ArrayList<>();
        private List<String> evidenceRefs = new ArrayList<>();
    }

    @Data
    @Accessors(chain = true)
    public static class RepairTask {
        private String taskCode;
        private String repairType;
        private String deviceCode;
        private String deviceName;
        private String faultClassName;
        private String faultDescription;
        private String faultReason;
        private String repairMethod;
        private String repairResult;
        private String state;
        private LocalDateTime submittedAt;
        private LocalDateTime expectedRecoveryAt;
        private String recoveryForecastReason;
        private LocalDateTime repairedAt;
    }

    @Data
    @Accessors(chain = true)
    public static class Verification {
        private String versionId;
        private String status;
        private String sourceFingerprint;
        private String verificationOperationId;
        private Boolean differentVerifier;
        private Boolean faultResolved;
        private Boolean trialVerified;
        private Boolean protectionVerified;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "TPM 异岗验证版本冻结的实际备件登记，须与 TPM 库存回执对账")
    public static class VerifiedPart {
        private String sparePartsCode;
        private String sparePartsName;
        private String unit;
        private BigDecimal useNum;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "TPM维修备件正式库存回执与异岗验证登记用量的对账结果")
    public static class SpareStockReconciliation {
        private Boolean receiptEvidenceAvailable;
        private Boolean quantitiesReconciled;
        private List<SpareStockReceipt> receipts = new ArrayList<>();
        private List<SpareStockBalance> balances = new ArrayList<>();
        private List<String> dataGaps = new ArrayList<>();
    }

    @Data
    @Accessors(chain = true)
    public static class SpareStockReceipt {
        private String receiptId;
        private String applicationId;
        private String applyCode;
        private String movementType;
        private String approvedByName;
        private LocalDateTime approvedTime;
        private String payloadFingerprint;
        private Integer recordCount;
    }

    @Data
    @Accessors(chain = true)
    public static class SpareStockBalance {
        private String sparePartsCode;
        private String sparePartsName;
        private String unit;
        private BigDecimal issuedNum;
        private BigDecimal returnedNum;
        private BigDecimal netIssuedNum;
        private BigDecimal verifiedUseNum;
        private Boolean reconciled;
    }
}
