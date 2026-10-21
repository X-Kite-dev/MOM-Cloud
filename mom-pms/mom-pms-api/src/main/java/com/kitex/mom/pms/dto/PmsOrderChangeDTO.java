package com.kitex.mom.pms.dto;

import com.kitex.mom.pms.entity.PmsOrderChangeLineEntity;
import com.kitex.mom.pms.entity.PmsOrderChangeOperationEntity;
import com.kitex.mom.pms.entity.PmsOrderChangeRequestEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class PmsOrderChangeDTO {
    private PmsOrderChangeDTO() {
    }

    @Data
    public static class SubmitCommand {
        private Long pmsOrderId;
        private String requestId;
        private String expectedEvidenceFingerprint;
        private String reason;
    }

    /**
     * 按采购订单行撤回尚未到货的商业供给，适用于一个订单承载多个计划需求的场景。
     */
    @Data
    public static class LineCancelPreviewCommand {
        private Long pmsOrderId;
        private List<Long> orderDetailIds;
    }

    /**
     * 行级供给撤回提交命令；证据指纹必须来自相同订单行集合的最新预览。
     */
    @Data
    public static class LineCancelSubmitCommand extends LineCancelPreviewCommand {
        private String requestId;
        private String expectedEvidenceFingerprint;
        private String reason;
    }

    @Data
    public static class ReviewCommand {
        private Long changeRequestId;
        private String requestId;
        private String expectedEvidenceFingerprint;
        private Boolean approved;
        private String reason;
    }

    @Data
    public static class AmendLineCommand {
        private Long orderDetailId;
        private BigDecimal targetOrderQuantity;
        private LocalDate targetDeliveryDate;
        private Long targetPriceId;
        private Long targetAuthorizationId;
    }

    @Data
    public static class AmendPreviewCommand {
        private Long pmsOrderId;
        private String targetSupplierCode;
        private List<AmendLineCommand> lines;
    }

    @Data
    public static class AmendSubmitCommand extends AmendPreviewCommand {
        private String requestId;
        private String expectedEvidenceFingerprint;
        private String reason;
    }

    @Data
    public static class PriceOption {
        private Long priceId;
        private BigDecimal unitPrice;
        private BigDecimal unitPriceWithoutTax;
        private BigDecimal unitPriceWithTax;
        private BigDecimal taxRate;
        private LocalDateTime effectiveTime;
        private LocalDateTime expirationTime;
    }

    @Data
    public static class AuthorizationOption {
        private Long authorizationId;
        private String authorizationNo;
        private LocalDate validStart;
        private LocalDate validEnd;
        private String inspectionLevel;
    }

    @Data
    public static class AmendLinePreview {
        private PmsOrderChangeLineEntity changeLine;
        private List<PriceOption> priceOptions;
        private List<AuthorizationOption> authorizationOptions;
        private List<String> blockers;
    }

    @Data
    public static class Preview {
        private Long pmsOrderId;
        private String pmsOrderNo;
        private String orderStatus;
        private Boolean eligible;
        private List<String> blockers;
        private String evidenceFingerprint;
        private String evidenceSnapshot;
        private BigDecimal totalOrderQuantity;
        private BigDecimal totalArrivalQuantity;
        private BigDecimal totalCancellableQuantity;
        private Integer receiptCount;
        private Integer activeReceiptCount;
        private Integer contractCount;
        private Integer settlementCount;
        private Integer activeCommitmentCount;
        private List<PmsOrderChangeLineEntity> lines;
        private String changeType;
        private Integer changeVersion;
        private String originalSupplierCode;
        private String originalSupplierName;
        private String targetSupplierCode;
        private String targetSupplierName;
        private BigDecimal totalTargetQuantity;
        private BigDecimal totalQuantityDelta;
        private Integer supplyAffectedLineCount;
        private List<AmendLinePreview> amendLines;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class Detail extends PmsOrderChangeRequestEntity {
        private List<PmsOrderChangeLineEntity> lines;
        private List<PmsOrderChangeOperationEntity> operations;
    }
}
