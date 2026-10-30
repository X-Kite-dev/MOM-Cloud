package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 工序不良率数据传输对象
 *
 * @author system
 * @since 2025-01-09
 */
@Data
@Schema(description = "所选建单期间工序任务的当前累计GOOD/NG快照；非期间检验事件或一次合格率，缺失证据返回null")
public class ProcessDefectRateDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 工序ID
     */
    @Schema(description = "工序ID")
    private String processId;

    /**
     * 工序编码
     */
    @Schema(description = "工序编码")
    private String processCode;

    /**
     * 工序名称
     */
    @Schema(description = "工序名称")
    private String processName;

    /**
     * 工序描述
     */
    @Schema(description = "工序描述")
    private String processDescription;

    /**
     * 总检验数量
     */
    @Schema(description = "总检验数量")
    private BigDecimal totalInspectionCount;

    /**
     * 不良品数量
     */
    @Schema(description = "不良品数量")
    private BigDecimal defectCount;

    /**
     * 合格品数量
     */
    @Schema(description = "合格品数量")
    private BigDecimal qualifiedCount;

    /**
     * 不良率（百分比）
     */
    @Schema(description = "不良率")
    private BigDecimal defectRate;

    /**
     * 不良率字符串（用于显示）
     */
    @Schema(description = "不良率字符串")
    private String defectRateStr;

    /**
     * 合格率（百分比）
     */
    @Schema(description = "合格率")
    private BigDecimal qualifiedRate;

    /**
     * 合格率字符串（用于显示）
     */
    @Schema(description = "合格率字符串")
    private String qualifiedRateStr;

    /**
     * 排名
     */
    @Schema(description = "排名")
    private Integer ranking;

    /**
     * 主要不良类型
     */
    @Schema(description = "主要不良类型")
    private String mainDefectType;

    /**
     * 主要不良原因
     */
    @Schema(description = "主要不良原因")
    private String mainDefectReason;

    /**
     * 趋势标识（up/down/stable）
     */
    @Schema(description = "趋势标识")
    private String trendFlag;

    /**
     * 与上期对比变化百分比
     */
    @Schema(description = "与上期对比变化百分比")
    private BigDecimal changePercentage;
}
