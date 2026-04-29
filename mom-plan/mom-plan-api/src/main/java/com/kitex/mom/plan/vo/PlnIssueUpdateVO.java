package com.kitex.mom.plan.vo;

import com.kitex.common.core.entity.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 月度预测修改日志VO
 *
 * @author kitex
 * @date 2025-02-25 15:21:03
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "月度预测修改日志VO")
public class PlnIssueUpdateVO extends BaseEntity<PlnIssueUpdateVO> {

    /**
     * 产品编码
     */
    @Schema(description = "产品编码")
    private String productCode;

    /**
     * 产品名称
     */
    @Schema(description = "产品名称")
    private String productName;

    /**
     * 操作人
     */
    @Schema(description = "操作人")
    private String operator;

    /**
     * 修改时间
     */
    @Schema(description = "修改时间")
    private LocalDateTime issueUpdateTime;

    /**
     * 数量
     */
    @Schema(description = "数量")
    private BigDecimal num;

    /**
     * 所属模块
     */
    @Schema(description = "所属模块")
    private String module;

    /**
     * 计划类型
     */
    @Schema(description = "计划类型")
    private String planType;
}