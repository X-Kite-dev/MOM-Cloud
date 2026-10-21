package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * MES工单由完成重新打开时提供给PLAN核验的不可变来源证据。
 */
@Data
@Accessors(chain = true)
@Schema(description = "MES完工重新打开来源证据")
public class MesPlanCompletionReopenEvidence {

    private Long tenantId;
    private String factoryCode;
    private Long outboxId;
    private String eventId;
    private Long reverseRequestId;
    private Long taskId;
    private String taskCode;
    private String planNo;
    private String apsBatchNo;
    private String productCode;
    private BigDecimal completeQuantityBefore;
    private BigDecimal completeQuantityAfter;
    private Integer completionVersionBefore;
    private Integer completionVersionAfter;
    private LocalDateTime reopenedTime;
    private String payloadFingerprint;
    private Boolean sourceValid;
    private List<SourceLine> sourceLines;

    /**
     * PLAN来源计划在本次重新打开后的聚合完成事实。
     */
    @Data
    @Accessors(chain = true)
    public static class SourceLine {
        private Long issueId;
        private String issueNo;
        private BigDecimal allocatedQuantity;
        private BigDecimal completedQuantity;
    }
}
