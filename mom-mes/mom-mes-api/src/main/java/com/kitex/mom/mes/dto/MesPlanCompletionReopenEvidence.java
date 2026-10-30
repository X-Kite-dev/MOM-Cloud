package com.kitex.mom.mes.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

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

    /**
     * 空值/1为原计划聚合来源；2为本次重新打开工单的完整来源分配。
     */
    private Integer sourceSchemaVersion;

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

    /**
     * 通知时刻固定平台秒级格式；工时仍由原执行区间计量。
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime reopenedTime;

    private String payloadFingerprint;
    private Boolean sourceValid;
    private List<SourceLine> sourceLines;

    /**
     * 来源数量事实；含义由sourceSchemaVersion确定，历史载荷不改写。
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
