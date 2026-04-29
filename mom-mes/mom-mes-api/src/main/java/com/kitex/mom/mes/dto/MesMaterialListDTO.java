package com.kitex.mom.mes.dto;

import com.kitex.mom.mes.entity.MesMaterialListEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * <p>
 * 用料清单主表 DTO类
 * </p>
 *
 * @author CodeLens
 * @since 2025-08-27
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Schema(description = "用料清单主表DTO")
public class MesMaterialListDTO extends MesMaterialListEntity {

    /**
     * 用料清单明细列表
     */
    @Schema(description = "用料清单明细列表")
    private List<MesMaterialListDetailDTO> materialDetails;

    /**
     * 工单状态名称
     */
    @Schema(description = "工单状态名称")
    private String taskStatusName;

    /**
     * 工单计划开始时间
     */
    @Schema(description = "工单计划开始时间")
    private String planStartDate;

    /**
     * 工单计划结束时间
     */
    @Schema(description = "工单计划结束时间")
    private String planEndDate;

    /**
     * 物料明细总数
     */
    @Schema(description = "物料明细总数")
    private Integer materialCount;

    /**
     * 已领料物料数
     */
    @Schema(description = "已领料物料数")
    private Integer pickedMaterialCount;

    /**
     * 待领料物料数
     */
    @Schema(description = "待领料物料数")
    private Integer pendingMaterialCount;
}