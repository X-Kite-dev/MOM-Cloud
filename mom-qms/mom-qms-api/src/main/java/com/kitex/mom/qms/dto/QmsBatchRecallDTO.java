package com.kitex.mom.qms.dto;

import com.kitex.mom.qms.entity.QmsBatchRecallActionEntity;
import com.kitex.mom.qms.entity.QmsBatchRecallEntity;
import com.kitex.mom.qms.entity.QmsBatchRecallOperationEntity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

/**
 * 批次召回候选从申请、复核、隔离、通知留痕到独立验证的受控命令。
 */
public final class QmsBatchRecallDTO {
    private QmsBatchRecallDTO() {
    }

    @Data
    @Accessors(chain = true)
    public static class ProposeCommand {
        @NotBlank
        @Size(max = 180)
        private String requestId;
        @NotBlank
        @Size(max = 200)
        private String materialCode;
        @NotBlank
        @Size(max = 200)
        private String batchNo;
        @NotBlank
        @Size(max = 30)
        private String severity;
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
        private Long recallId;
        @NotNull
        private Boolean approved;
        @NotBlank
        @Size(max = 64)
        private String expectedEvidenceHash;
        @NotBlank
        @Size(max = 2000)
        private String remark;
    }

    @Data
    @Accessors(chain = true)
    public static class ExecuteCommand {
        @NotBlank
        @Size(max = 180)
        private String requestId;
        @NotNull
        private Long recallId;
    }

    @Data
    @Accessors(chain = true)
    public static class NotificationCommand {
        @NotBlank
        @Size(max = 180)
        private String requestId;
        @NotNull
        private Long recallId;
        @NotBlank
        @Size(max = 60)
        private String targetType;
        @NotBlank
        @Size(max = 300)
        private String targetReference;
        @NotBlank
        @Size(max = 200)
        private String notificationReference;
        @NotBlank
        @Size(max = 4000)
        private String evidence;
        @NotBlank
        @Size(max = 1000)
        private String remark;
    }

    @Data
    @Accessors(chain = true)
    public static class VerifyCommand {
        @NotBlank
        @Size(max = 180)
        private String requestId;
        @NotNull
        private Long recallId;
        @NotBlank
        @Size(max = 2000)
        private String remark;
    }

    @Data
    @Accessors(chain = true)
    public static class Detail {
        private QmsBatchRecallEntity recall;
        private List<QmsBatchRecallActionEntity> actions = new ArrayList<>();
        private List<QmsBatchRecallOperationEntity> operations = new ArrayList<>();
    }
}
