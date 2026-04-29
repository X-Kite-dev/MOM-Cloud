package com.kitex.mom.pms.dto;

import com.kitex.mom.pms.entity.PmsSupplierAptitudeEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 供应商DTO
 *
 * @author kitex
 * @date 2025-03-19 09:22:40
 */
@Data
@Schema(description = "供应商DTO")
public class PmsSupplierDTO {

    /**
     * 供应商详情id
     */
    @Schema(description = "供应商详情id")
    private Long id;

    /**
     * 供应商主键id
     */
    @Schema(description = "供应商主键id")
    private Long supplierId;

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
     * 状态 (1=启用, 0=禁用)
     */
    @Schema(description = "状态 (1=启用, 0=禁用)")
    private Integer status;

    /**
     * 审核状态(0 待审核, 1 通过, 2 驳回)
     */
    @Schema(description = "审核状态(0 待审核, 1 通过, 2 驳回)")
    private String checkStatus;

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
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    private String factoryCode;

    /**
     * 供应商资质列表
     */
    @Schema(description = "供应商资质列表")
    private List<PmsSupplierAptitudeEntity> aptitudeList;
}