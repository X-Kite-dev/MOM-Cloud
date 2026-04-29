package com.kitex.mom.mes.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * <p>
 * 生产订单排程 VO类
 * </p>
 *
 * @author CodeLens
 * @since 2025-08-27
 */
@Data
@Schema(description = "生产订单排程VO")
public class MesProductionOrderScheduleVO {

    /**
     * 生产订单ID
     */
    @Schema(description = "生产订单ID")
    @NotNull(message = "生产订单ID不能为空")
    private Long orderId;

    /**
     * 排程数量
     */
    @Schema(description = "排程数量")
    @NotNull(message = "排程数量不能为空")
    @Positive(message = "排程数量必须大于0")
    private BigDecimal scheduledQuantity;

    /**
     * 排程开始时间
     */
    @Schema(description = "排程开始时间")
    @NotNull(message = "排程开始时间不能为空")
    private LocalDateTime scheduledStartDate;

    /**
     * 排程结束时间
     */
    @Schema(description = "排程结束时间")
    @NotNull(message = "排程结束时间不能为空")
    private LocalDateTime scheduledEndDate;

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
     * 排程产线编码
     */
    @Schema(description = "排程产线编码")
    private String productLineCode;

    /**
     * 排程产线名称
     */
    @Schema(description = "排程产线名称")
    private String productLineName;

    /**
     * 排程车间编码
     */
    @Schema(description = "排程车间编码")
    private String workshopCode;

    /**
     * 排程车间名称
     */
    @Schema(description = "排程车间名称")
    private String workshopName;

    /**
     * 排程优先级
     */
    @Schema(description = "排程优先级")
    private Integer priority;

    /**
     * 排程类型：AUTO-自动排程，MANUAL-手工排程
     */
    @Schema(description = "排程类型：AUTO-自动排程，MANUAL-手工排程")
    private String scheduleType;

    /**
     * 备注
     */
    @Schema(description = "备注")
    private String remark;

    /**
     * 是否强制排程（忽略产能冲突）
     */
    @Schema(description = "是否强制排程（忽略产能冲突）")
    private Boolean forceSchedule;

    /**
     * 排程策略（可扩展）
     */
    @Schema(description = "排程策略")
    private String scheduleStrategy;
}