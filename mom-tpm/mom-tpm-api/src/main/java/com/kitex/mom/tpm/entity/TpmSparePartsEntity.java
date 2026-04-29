package com.kitex.mom.tpm.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.util.List;

/**
 * 备件表
 *
 * @author kitex
 * @date 2025-02-11 16:39:20
 */
@Data
@TenantTable
@TableName("tpm_spare_parts")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "备件表")
public class TpmSparePartsEntity extends BaseEntity<TpmSparePartsEntity> {


    /**
     * id
     */
    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "id")
    private Long id;

    /**
     * 编码
     */
    @Schema(description = "编码")
    private String code;

    /**
     * 名称
     */
    @Schema(description = "名称")
    private String name;

    /**
     * 规格型号
     */
    @Schema(description = "规格型号")
    private String spec;

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
     * 供应商联系人
     */
    @Schema(description = "供应商联系人")
    private String supplierConcat;

    /**
     * 供应商电话
     */
    @Schema(description = "供应商电话")
    private String supplierPhone;

    /**
     * 最小安全库存
     */
    @Schema(description = "最小安全库存")
    private BigDecimal safetyStockMin;

    /**
     * 最大安全库存
     */
    @Schema(description = "最大安全库存")
    private BigDecimal safetyStockMax;

    /**
     * 单位
     */
    @Schema(description = "单位")
    private String unit;

    /**
     * 备注
     */
    @Schema(description = "备注")
    private String remark;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;


    @TableField(exist = false)
    @Schema(description = "排除备件编码集合")
    private List<String> neCodeList;
}