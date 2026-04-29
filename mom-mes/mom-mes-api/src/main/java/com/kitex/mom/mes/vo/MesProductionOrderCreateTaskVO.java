package com.kitex.mom.mes.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * <p>
 * 从生产订单创建工单 VO类
 * </p>
 *
 * @author CodeLens
 * @since 2025-08-27
 */
@Data
@Schema(description = "从生产订单创建工单VO")
public class MesProductionOrderCreateTaskVO {

    /**
     * 生产订单ID
     */
    @Schema(description = "生产订单ID")
    @NotNull(message = "生产订单ID不能为空")
    private Long orderId;

    /**
     * 排程ID（可选，基于排程创建工单时提供）
     */
    @Schema(description = "排程ID（可选，基于排程创建工单时提供）")
    private Long scheduleId;

    /**
     * 工单数量
     */
    @Schema(description = "工单数量")
    @NotNull(message = "工单数量不能为空")
    @Positive(message = "工单数量必须大于0")
    private BigDecimal taskQuantity;

    /**
     * 选择的工艺路线ID
     */
    @Schema(description = "选择的工艺路线ID")
    @NotNull(message = "工艺路线ID不能为空")
    private Long routeId;

    /**
     * 选择的工艺路线编码
     */
    @Schema(description = "选择的工艺路线编码")
    private String routeCode;

    /**
     * 创建类型：SCHEDULE-排程创建，MANUAL-手工创建
     */
    @Schema(description = "创建类型：SCHEDULE-排程创建，MANUAL-手工创建")
    private String createType;

    /**
     * 关联类型：MAIN-主工单，SUB-子工单
     */
    @Schema(description = "关联类型：MAIN-主工单，SUB-子工单")
    private String relationType;

    /**
     * 是否拆分工单
     */
    @Schema(description = "是否拆分工单")
    private Boolean isSplit;

    /**
     * 拆分数量列表（如果拆分工单）
     */
    @Schema(description = "拆分数量列表（如果拆分工单）")
    private List<BigDecimal> splitQuantities;

    /**
     * 备注
     */
    @Schema(description = "备注")
    private String remark;
}