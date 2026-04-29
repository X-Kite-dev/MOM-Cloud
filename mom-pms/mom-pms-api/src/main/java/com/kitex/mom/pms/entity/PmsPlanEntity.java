package com.kitex.mom.pms.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * 采购计划表
 *
 * @author kitex
 * @date 2025-01-07 09:42:33
 */
@Data
@TenantTable
@TableName("pms_plan")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "采购计划表")
public class PmsPlanEntity extends BaseEntity<PmsPlanEntity> {

    /**
     * 采购计划号
     */
    @Schema(description = "采购计划号")
    private String pmsPlanNo;

    /**
     * 采购计划下达号
     */
    @Schema(description = "采购计划下达号")
    private String orderNo;

    /**
     * 物料编码
     */
    @Schema(description = "物料编码")
    private String matCode;

    /**
     * 物料名称
     */
    @Schema(description = "物料名称")
    private String matName;

    /**
     * 物料单位
     */
    @Schema(description = "物料单位")
    private String matUnit;

    /**
     * 物料规格
     */
    @Schema(description = "物料规格")
    private String matSpec;

    /**
     * 物料类型
     */
    @Schema(description = "物料类型")
    private String matType;

    /**
     * 采购数量
     */
    @Schema(description = "采购数量")
    private Double pmsNum;

    /**
     * 状态（0 未下发 1 下发中 2 已下发 3 完成）
     */
    @Schema(description = "状态（0 未下发 1 下发中 2 已下发 3 完成）")
    private String status;

    /**
     * 要货时间
     */
    @Schema(description = "要货时间")
    private LocalDate planDeliveryTime;

    /**
     * 下单数量
     */
    @Schema(description = "下单数量")
    private Double planOrderNum;

    /**
     * 到货数量
     */
    @Schema(description = "到货数量")
    private Double planArrivalNum;

    /**
     * 质检合格状态，true 合格 false 不合格
     */
    @Schema(description = "质检合格状态，true 合格 false 不合格")
    private Boolean quality;

    /**
     * 质检说明
     */
    @Schema(description = "质检说明")
    private String qualityReason;

    /**
     * 类型 采购 外协
     */
    @Schema(description = "类型 采购 外协")
    private String type;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}