package com.kitex.mom.mdm.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.util.TenantTable;
import com.kitex.mom.mdm.base.BusinessEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 制造模型
 * 车间，报工方式，是否班组任务单，是否夸线体工位报工，不良处理方式，返修模式
 */
@Data
@TenantTable
@TableName("mdm_manufacturing_model")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "制造模型")
public class MdmManufacturingModelEntity extends BusinessEntity<MdmManufacturingModelEntity> {

    /**
     * 车间编码
     */
    @Schema(description = "车间编码")
    private String workshopCode;

    /**
     * 车间名称
     */
    @Schema(description = "车间名称")
    private String workshopName;

    /**
     * 报工方式
     */
    @Schema(description = "报工方式")
    private String reportType;

    /**
     * 报工方式
     */
    @Schema(description = "报工方法")
    private String reportWay;

    /**
     * 是否班组任务单
     */
    @Schema(description = "是否班组任务单")
    private Boolean teamTask;

    /**
     * 是否夸线体工位报工
     */
    @Schema(description = "是否夸线体工位报工")
    private Boolean crossLineStation;

    /**
     * 不良处理方式
     */
    @Schema(description = "不良处理方式")
    private String ngHandle;


    /**
     * 返修模式
     */
    @Schema(description = "返修模式")
    private String repairMode;


}
