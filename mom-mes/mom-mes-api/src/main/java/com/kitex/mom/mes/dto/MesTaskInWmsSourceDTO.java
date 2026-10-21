package com.kitex.mom.mes.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 成品入库前按末工序报工计算的精确数量来源。
 */
@Data
@Accessors(chain = true)
public class MesTaskInWmsSourceDTO {
    /**
     * 生产工单主键。
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long taskId;

    /**
     * 生产工单编码。
     */
    private String taskCode;

    /**
     * 用户本次准备入库的数量。
     */
    private BigDecimal requestQuantity;

    /**
     * 预览时工单累计已入库数量。
     */
    private BigDecimal currentInStockQuantity;

    /**
     * 预览时不可变来源明细累计数量，应与已入库数量相等。
     */
    private BigDecimal currentTrackedQuantity;

    /**
     * 本次来源分配的确定性SHA-256指纹，提交时必须原样带回。
     */
    private String evidenceFingerprint;

    /**
     * 按报工时间和主键稳定FIFO计算出的来源分配行。
     */
    private List<Line> lines;

    /**
     * 单条报工在本次入库中承担的数量。
     */
    @Data
    @Accessors(chain = true)
    public static class Line {
        /**
         * 原始报工主键。
         */
        @JsonSerialize(using = ToStringSerializer.class)
        private Long reportId;

        /**
         * 原始报工永久业务请求号。
         */
        private String reportRequestId;

        /**
         * 来源末工序派工单编码。
         */
        private String arrangeCode;

        /**
         * 来源末工序任务编码。
         */
        private String produceTaskCode;

        /**
         * 该报工为本次入库承担的数量。
         */
        private BigDecimal quantity;

        /**
         * 预览时的质量数量状态。
         */
        private String qualityQuantityStatus;

        /**
         * 本报工实际依赖的QMS任务ID JSON数组。
         */
        private String qualityTaskIds;

        /**
         * QMS质量证据SHA-256指纹。
         */
        private String qualityEvidenceFingerprint;

        /**
         * 原始报工发生时间。
         */
        private LocalDateTime reportTime;

        /**
         * 原始报工核心事实指纹。
         */
        private String sourceFingerprint;
    }
}
