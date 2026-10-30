package com.kitex.mom.mdm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 维修任务的MDM当前设备资格只读投影；不能作为设备开机命令或MES复产批准。
 */
public final class MdmRepairAgentEvidence {

    private MdmRepairAgentEvidence() {
    }

    @Data
    @Accessors(chain = true)
    public static class Query {
        @NotBlank
        private String factoryCode;
        @NotBlank
        private String repairTaskId;
        @NotBlank
        private String verificationId;
        @NotBlank
        private String deviceCode;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "MDM当前设备资格和同维修任务的正式恢复回执")
    public static class Result {
        private String factoryCode;
        private String repairTaskId;
        private String verificationId;
        private String deviceCode;
        private String deviceId;
        private String deviceState;
        private String deviceUsable;
        private Boolean deviceEnabled;
        private String recoveryReceiptId;
        private LocalDateTime recoveredAt;
        private Boolean qualificationVerified;
        private LocalDateTime observedAt;
        private String factFingerprint;
        private List<String> dataGaps = new ArrayList<>();
        private List<String> evidenceRefs = new ArrayList<>();
    }
}
