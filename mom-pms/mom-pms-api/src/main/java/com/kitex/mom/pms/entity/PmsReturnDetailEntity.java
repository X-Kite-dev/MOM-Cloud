package com.kitex.mom.pms.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 采购退货
 *
 * @author kitex
 * @date 2025-02-24 08:38:40
 */
@Data
@TableName("pms_return_detail")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "pms_return_detail")
public class PmsReturnDetailEntity extends BaseEntity<PmsReturnDetailEntity> {
    /**
     * 采购退货id
     */
    @Schema(description = "采购退货id")
    private Long returnId;
    /**
     * 采购订单明细id
     */
    @Schema(description = "采购订单明细id")
    private Long orderDetailId;
    /**
     * 采购计划号
     */
    @Schema(description = "采购计划号")
    private String planNo;
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
     * 退货数量
     */
    @Schema(description = "退货数量")
    private Double returnNum;
    /**
     * 工厂编码
     */
    @Schema(description = "工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}