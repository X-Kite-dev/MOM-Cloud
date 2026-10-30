package com.kitex.mom.oms.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.kitex.mom.oms.entity.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 交期答复工作台的查询结果和受控命令。
 */
public final class OmsDeliveryPromiseDTO {
    private OmsDeliveryPromiseDTO() {
    }

    @Data
    @Accessors(chain = true)
    public static class CandidateLine {
        private Long sourceDetailId;
        @Size(max = 200)
        private String productCode;
        @Size(max = 300)
        private String productName;
        @Size(max = 300)
        private String productSpec;
        @Size(max = 100)
        private String productUnit;
        @NotNull
        @JsonFormat(pattern = "yyyy-MM-dd")
        private LocalDate proposedDeliverTime;
        @NotNull
        @DecimalMin(value = "0.00000001")
        private BigDecimal proposedDeliverNum;
        @Size(max = 500)
        private String remark;
    }

    @Data
    @Accessors(chain = true)
    public static class SubmitCommand {
        @NotBlank
        @Size(max = 180)
        private String requestId;
        @NotBlank
        @Size(max = 100)
        private String orderCode;
        @NotBlank
        @Size(max = 30)
        private String sourceType;
        @NotBlank
        @Size(max = 200)
        private String customerRequestRef;
        @NotBlank
        @Size(max = 2000)
        private String reason;
        @NotNull
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime replyDueTime;
        @Valid
        @NotEmpty
        @Size(max = 100)
        private List<CandidateLine> lines = new ArrayList<>();
    }

    @Data
    @Accessors(chain = true)
    public static class ConfirmCommand {
        @NotBlank
        @Size(max = 180)
        private String requestId;
        @NotNull
        private Long promiseId;
        @NotNull
        private Boolean approved;
        @NotBlank
        @Size(max = 64)
        private String expectedFactHash;
        @NotBlank
        @Size(max = 64)
        private String expectedCandidateHash;
        @NotBlank
        @Size(max = 2000)
        private String remark;
    }

    @Data
    @Accessors(chain = true)
    public static class CancelCommand {
        @NotBlank
        @Size(max = 180)
        private String requestId;
        @NotNull
        private Long promiseId;
        @NotBlank
        @Size(max = 1000)
        private String reason;
    }

    @Data
    @Accessors(chain = true)
    public static class ReplyCommand {
        @NotBlank
        @Size(max = 180)
        private String requestId;
        @NotNull
        private Long promiseId;
        @NotBlank
        @Size(max = 30)
        private String channel;
        @NotBlank
        @Size(max = 300)
        private String reference;
        @NotBlank
        @Size(max = 4000)
        private String content;
    }

    @Data
    @Accessors(chain = true)
    public static class Preparation {
        private OmsOrderBaseTrackEntity order;
        private List<OmsOrderDetailsEntity> currentLines = new ArrayList<>();
        private String factHash;
        private String candidateHash;
    }

    @Data
    @Accessors(chain = true)
    public static class Detail {
        private OmsDeliveryPromiseEntity promise;
        private List<OmsDeliveryPromiseLineEntity> lines = new ArrayList<>();
        private List<OmsDeliveryPromiseOperationEntity> operations = new ArrayList<>();
    }
}
