package com.kitex.mom.qms.dto;

import com.kitex.mom.qms.entity.QmsFinishedGoodsDecisionReversalEntity;
import com.kitex.mom.qms.entity.QmsFinishedGoodsDecisionReversalOperationEntity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 成品入库质量判定逆向申请、复核和WMS幂等消费命令。
 */
public final class QmsFinishedGoodsDecisionReversalDTO {
    private QmsFinishedGoodsDecisionReversalDTO() {
    }

    @Data
    @Accessors(chain = true)
    public static class ApplyCommand {
        @NotBlank
        @Size(max = 180)
        private String requestId;
        @NotNull
        private Long wmsNoticeId;
        @NotEmpty
        @Size(max = 1000)
        private List<Long> sourceQualityTaskIds;
        @NotBlank
        @Size(min = 64, max = 64)
        private String sourceQualityEvidenceFingerprint;
        @NotBlank
        @Size(max = 2000)
        private String reason;
    }

    @Data
    @Accessors(chain = true)
    public static class ReviewCommand {
        @NotBlank
        @Size(max = 180)
        private String requestId;
        @NotNull
        private Long reversalId;
        @NotNull
        private Boolean approved;
        @NotBlank
        @Size(min = 64, max = 64)
        private String expectedEvidenceFingerprint;
        @NotBlank
        @Size(max = 2000)
        private String remark;
    }

    @Data
    @Accessors(chain = true)
    public static class WithdrawCommand {
        @NotBlank
        @Size(max = 180)
        private String requestId;
        @NotBlank
        @Size(max = 2000)
        private String reason;
    }

    /**
     * WMS查询当前精确来源是否已经取得可消费的质量授权。
     */
    @Data
    @Accessors(chain = true)
    public static class AuthorizationQuery {
        @NotNull
        private Long wmsNoticeId;
        @NotEmpty
        @Size(max = 1000)
        private List<Long> sourceQualityTaskIds;
        @NotBlank
        @Size(min = 64, max = 64)
        private String sourceQualityEvidenceFingerprint;
        @Size(max = 100)
        private String factoryCode;
    }

    /**
     * WMS使用永久请求号一次性消费质量授权。
     */
    @Data
    @Accessors(chain = true)
    @EqualsAndHashCode(callSuper = true)
    public static class ConsumeCommand extends AuthorizationQuery {
        @NotNull
        private Long authorizationId;
        @NotBlank
        @Size(max = 64)
        private String authorizationCode;
        @NotBlank
        @Size(max = 180)
        private String wmsCorrectionRequestId;
    }

    @Data
    @Accessors(chain = true)
    public static class Authorization {
        private Long reversalId;
        private String reversalNo;
        private String status;
        private Boolean eligible = false;
        private String message;
        private String authorizationCode;
        private LocalDateTime authorizationExpiresAt;
        private String qmsEvidenceFingerprint;
        private String applicantName;
        private String reviewerName;
    }

    @Data
    @Accessors(chain = true)
    public static class Detail {
        private QmsFinishedGoodsDecisionReversalEntity reversal;
        private List<QmsFinishedGoodsDecisionReversalOperationEntity> operations = new ArrayList<>();
    }
}
