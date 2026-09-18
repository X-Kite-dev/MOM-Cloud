package com.kitex.mom.mdm.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 工艺路线和工序关联
 *
 * @author kitex
 * @date 2024-11-01 13:44:51
 */
@Data
@TenantTable
@TableName("mdm_process_route_working")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "工艺路线和工序关联")
public class MdmProcessRouteWorkingEntity extends BaseEntity<MdmProcessRouteWorkingEntity> {

    /**
     * 工艺路线编码
     */
    @Schema(description = "工艺路线编码")
    private String routeCode;

    /**
     * 工艺路线名称
     */
    @Schema(description = "工艺路线名称")
    private String routeName;

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
     * 顺序
     */
    @Schema(description = "顺序")
    private Integer orderNum;

    /**
     * 前置工序编码
     */
    @Schema(description = "前置工序编码")
    private String[] preRouteCode;


    /**
     * 后置工序编码
     */
    private String[] nextRouteCode;

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
     * 类型 一物一码/数量
     */
    @Schema(description = "类型 一物一码/数量")
    private String type;

    /**
     * 是否控制转序时间
     */
    @Schema(description = "是否控制转序时间")
    private Boolean timeCheck;

    /**
     * 转序时间要求（分钟）
     */
    @Schema(description = "转序时间要求（分钟）")
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal timeVal;

    @Schema(description = "允许达到转移批量后与后序重叠执行")
    private Boolean overlapEnabled;

    @Schema(description = "放行后序开工的最小合格转移批量")
    private BigDecimal transferBatchQuantity;

    /**
     * 工厂编码
     */
    @Schema(description = "工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
