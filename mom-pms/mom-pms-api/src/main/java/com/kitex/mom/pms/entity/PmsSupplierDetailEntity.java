package com.kitex.mom.pms.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 供应商详情表
 *
 * @author kitex
 * @date 2025-03-19 08:56:36
 */
@Data
@TenantTable
@TableName("pms_supplier_detail")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "供应商详情表")
public class PmsSupplierDetailEntity extends BaseEntity<PmsSupplierDetailEntity> {

    /**
     * 供应商主键id
     */
    @Schema(description = "供应商主键id")
    private Long supplierId;

    /**
     * 统一社会信用代码
     */
    @Schema(description = "统一社会信用代码")
    private String creditCode;

    /**
     * 简称
     */
    @Schema(description = "简称")
    private String shortName;

    /**
     * 服务范围描述
     */
    @Schema(description = "服务范围描述")
    private String serviceScope;

    /**
     * 企业性质
     */
    @Schema(description = "企业性质")
    private String companyNature;

    /**
     * 主要联系人姓名
     */
    @Schema(description = "主要联系人姓名")
    private String contactPerson;

    /**
     * 性别
     */
    @Schema(description = "性别")
    private String gender;

    /**
     * 职务
     */
    @Schema(description = "职务")
    private String position;

    /**
     * 邮箱地址
     */
    @Schema(description = "邮箱地址")
    private String email;

    /**
     * 手机号
     */
    @Schema(description = "手机号")
    private String phoneNumber;

    /**
     * 法人
     */
    @Schema(description = "法人")
    private String legalRepresentative;

    /**
     * 法人身份证号
     */
    @Schema(description = "法人身份证号")
    private String idNumber;

    /**
     * 公司成立时间
     */
    @Schema(description = "公司成立时间")
    private LocalDate establishmentDate;

    /**
     * 注册资金
     */
    @Schema(description = "注册资金")
    private BigDecimal registeredCapital;

    /**
     * 营业执照编号
     */
    @Schema(description = "营业执照编号")
    private String businessLicense;

    /**
     * 证件有效截止日期
     */
    @Schema(description = "证件有效截止日期")
    private LocalDate licenseExpiryDate;

    /**
     * 工商注册地址
     */
    @Schema(description = "工商注册地址")
    private String registeredAddress;

    /**
     * 座机号码
     */
    @Schema(description = "座机号码")
    private String landline;

    /**
     * 传真号码
     */
    @Schema(description = "传真号码")
    private String fax;

    /**
     * 所属国家
     */
    @Schema(description = "所属国家")
    private String country;

    /**
     * 所属地区
     */
    @Schema(description = "所属地区")
    private String region;

    /**
     * 信用等级
     */
    @Schema(description = "信用等级")
    private String creditRating;

    /**
     * 工厂编码
     */
    @Schema(description = "工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}