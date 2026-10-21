package com.kitex.mom.mdm.dto;

import com.kitex.mom.mdm.entity.MdmDurationCalibrationProposalEntity;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public final class MdmDurationCalibrationDTO {
    private MdmDurationCalibrationDTO() {
    }

    @Data
    public static class PolicyAction {
        private Long id;
        private String action;
        private String reason;
    }

    @Data
    public static class RunCommand {
        private Long policyId;
        private LocalDateTime fromTime;
        private LocalDateTime toTime;
    }

    @Data
    public static class ProposalUpdate {
        private Long id;
        private String scopeType;
        private String resourceCode;
        private String processCode;
        private String fromFamilyCode;
        private String toFamilyCode;
        private Long proposedFixedSeconds;
        private BigDecimal proposedUnitSeconds;
        private Long proposedChangeoverSeconds;
        private BigDecimal cleaningRatioPct;
        private LocalDateTime effectiveTime;
        private String reviewComment;
    }

    @Data
    public static class ProposalAction {
        private Long id;
        private String action;
        private String reason;
    }

    @Data
    public static class CatalogQuery {
        private String factoryCode;
        private LocalDateTime effectiveTime;
        private List<String> processCodes;
    }

    @Data
    @Accessors(chain = true)
    public static class Catalog {
        private LocalDateTime generatedTime;
        private String fingerprint;
        private List<MdmDurationCalibrationProposalEntity> profiles;
    }
}
