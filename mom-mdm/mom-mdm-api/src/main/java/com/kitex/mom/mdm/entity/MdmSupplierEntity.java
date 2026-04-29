package com.kitex.mom.mdm.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 供应商
 *
 * @author kitex
 * @date 2024-10-25 14:17:27
 */
@Data
@TenantTable
@TableName("mdm_supplier")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "供应商")
public class MdmSupplierEntity extends BaseEntity<MdmSupplierEntity> {

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
     * 供应商分类编码
     */
    @Schema(description = "供应商分类编码")
    private String supplierClassCode;

    /**
     * 供应商分类名称
     */
    @Schema(description = "供应商分类名称")
    private String supplierClassName;

    /**
     * 状态 (true=启用, false=禁用)
     */
    @Schema(description = "状态 (true=启用, false=禁用)")
    private Boolean status;

    /**
     * 审核状态(0 待审核, 1 通过, 2 驳回)
     */
    @Schema(description = "审核状态(0 待审核, 1 通过, 2 驳回)")
    private String checkStatus;

    /**
     * 审核意见
     */
    @Schema(description = "审核意见")
    private String checkRemark;

    /**
     * 是否供货 (1=是, 0=否)
     */
    @Schema(description = "是否供货 (1=是, 0=否)")
    private Boolean isDeliver;

    /**
     * 供应商类型
     */
    @Schema(description = "供应商类型")
    private String supplierType;

    /**
     * 开户行
     */
    @Schema(description = "开户行")
    private String bank;

    /**
     * 收款账户
     */
    @Schema(description = "收款账户")
    private String account;

    /**
     * 税种
     */
    @Schema(description = "税种")
    private String taxType;

    /**
     * 税率
     */
    @Schema(description = "税率")
    private BigDecimal taxRate;

    /**
     * 主要供应产品
     */
    @Schema(description = "主要供应产品")
    private String mainProducts;

    /**
     * 详细地址
     */
    @Schema(description = "详细地址")
    private String detailedAddress;

    /**
     * 交货提前期（天）
     */
    @Schema(description = "交货提前期（天）")
    private Integer deliveryLeadTime;

    /**
     * 交货周期（天）
     */
    @Schema(description = "交货周期（天）")
    private Integer deliveryCycle;

    /**
     * 委外发料周期（天）
     */
    @Schema(description = "委外发料周期（天）")
    private Integer outsourcingIssuanceCycle;

    /**
     * 月初委外发料周期（天）
     */
    @Schema(description = "月初委外发料周期（天）")
    private Integer beginningOfMonthOutsourcingIssuanceCycle;

    /**
     * 交货方式
     */
    @Schema(description = "交货方式")
    private String deliveryMethod;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

}