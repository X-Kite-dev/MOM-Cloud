package com.kitex.mom.plan.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** MRP生成的下层自制、采购或外协供应单据的受控撤回契约。 */
public final class PlanMrpSupplyWithdrawalDTO {
    private PlanMrpSupplyWithdrawalDTO() {}

    @Data
    @Accessors(chain = true)
    public static class Evidence {
        private Long mrpId;
        private String materialCode;
        private String supplyType;
        private Long sourceId;
        private String sourceNo;
        private BigDecimal sourceQuantity;
        private String sourceStatus;
        /** NOT_APPLICABLE、ELIGIBLE、ALREADY_WITHDRAWN。 */
        private String status;
        private String evidenceFingerprint;
        private String downstreamEvidenceFingerprint;
        private String evidenceSnapshot;
        private String message;
    }

    @Data
    @Accessors(chain = true)
    public static class Command {
        private Long mrpId;
        private String requestId;
        private String expectedEvidenceFingerprint;
        /** 申请/复核时冻结的PMS或MES下游证据；执行及远端成功、本地回滚后的重试均据此安全续跑。 */
        private String expectedDownstreamEvidenceFingerprint;
        private String reason;
    }

    @Data
    @Accessors(chain = true)
    @EqualsAndHashCode(callSuper = true)
    public static class Receipt extends Evidence {
        private LocalDateTime handledTime;
    }
}
