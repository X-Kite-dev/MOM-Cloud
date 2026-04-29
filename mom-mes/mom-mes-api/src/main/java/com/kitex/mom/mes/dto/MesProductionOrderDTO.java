package com.kitex.mom.mes.dto;

import com.kitex.mom.mes.entity.MesProductionOrderEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.util.List;

/**
 * <p>
 * 生产订单主表 DTO类
 * </p>
 *
 * @author CodeLens
 * @since 2025-08-27
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Schema(description = "生产订单主表DTO")
public class MesProductionOrderDTO extends MesProductionOrderEntity {
    /*
     * 状态名称
     */
    @Schema(description = "状态名称")
    private String statusName;

    /**
     * 订单类型名称
     */
    @Schema(description = "订单类型名称")
    private String orderTypeName;

    /**
     * 来源类型名称
     */
    @Schema(description = "来源类型名称")
    private String sourceTypeName;

    /**
     * 完成百分比
     */
    @Schema(description = "完成百分比")
    private BigDecimal completionRate;

    /**
     * 是否已排程
     */
    @Schema(description = "是否已排程")
    private Boolean isScheduled;

    /**
     * 是否已下达
     */
    @Schema(description = "是否已下达")
    private Boolean isReleased;

    /**
     * 工单数量
     */
    @Schema(description = "工单数量")
    private Integer taskCount;

    /**
     * 延期天数
     */
    @Schema(description = "延期天数")
    private Integer delayDays;


    @Schema(description = "工单列表")
    private List<MesTaskDTO> taskList;
}