package com.kitex.mom.pms.entity;

import com.alibaba.excel.annotation.ExcelIgnore;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.github.yulichang.annotation.EntityMapping;
import com.kitex.common.core.entity.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 采购退货
 *
 * @author kitex
 * @date 2025-02-24 08:38:40
 */
@Data
@TableName("pms_return")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "采购退货")
public class PmsReturnEntity extends BaseEntity<PmsReturnEntity> {

    /**
     * 采购退货号
     */
    @Schema(description = "采购退货号")
    private String returnNo;

    /**
     * 采购订单号
     */
    @Schema(description = "采购订单号")
    private String orderNo;

    /**
     * 供应商编码
     */
    @Schema(description = "供应商编码")
    private String supplierCode;

    /**
     * 供应商名称
     */
    @Schema(description = "供应商名称")
    private String supplierName;

    /**
     * 工厂编码
     */
    @Schema(description = "工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

    /**
     * 是否通知仓库
     */
    @Schema(description = "是否通知仓库")
    private Boolean warehouse;

    /**
     * 是否已通知
     */
    @Schema(description = "是否已通知")
    private Boolean warehouseStatus;

    /**
     * 备注
     */
    @Schema(description = "备注")
    private String remark;

    @ExcelIgnore
    @TableField(exist = false)
    @EntityMapping(thisField = "id", joinField = "returnId")
    private List<PmsReturnDetailEntity> pmsReturnDetailList;
}