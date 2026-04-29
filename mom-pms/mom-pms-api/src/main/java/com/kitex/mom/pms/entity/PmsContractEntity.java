package com.kitex.mom.pms.entity;

import com.alibaba.excel.annotation.ExcelIgnore;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.github.yulichang.annotation.EntityMapping;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 合同管理
 *
 * @author kitex
 * @date 2025-04-02 16:10:18
 */
@Data
@TenantTable
@TableName("pms_contract")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "合同管理")
public class PmsContractEntity extends BaseEntity<PmsContractEntity> {

    /**
     * 采购订单号
     */
    @Schema(description = "采购订单号")
    private String pmsOrderNo;

    /**
     * 合同模板编码
     */
    @Schema(description = "合同模板编码")
    private String templateCode;

    /**
     * 合同模板名称
     */
    @Schema(description = "合同模板名称")
    private String templateName;

    /**
     * 标题
     */
    @Schema(description = "标题")
    private String title;

    /**
     * 合同名称
     */
    @Schema(description = "合同名称")
    private String contractName;

    /**
     * 签约主体
     */
    @Schema(description = "签约主体")
    private String contractManager;

    /**
     * 合同编号
     */
    @Schema(description = "合同编号")
    private String contractNo;

    /**
     * 合同生效日期
     */
    @Schema(description = "合同生效日期")
    private LocalDateTime contractDate;

    /**
     * 合同总金额
     */
    @Schema(description = "合同总金额")
    private BigDecimal contractAmount;

    /**
     * 合同重要条款说明
     */
    @Schema(description = "合同重要条款说明")
    private String contractDesc;

    /**
     * 合同需求说明
     */
    @Schema(description = "合同需求说明")
    private String contractDemand;

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
     * 法人
     */
    @Schema(description = "法人")
    private String legalRepresentative;

    /**
     * 地址
     */
    @Schema(description = "地址")
    private String address;

    /**
     * 开户行
     */
    @Schema(description = "开户行")
    private String bank;

    /**
     * 开户行账户
     */
    @Schema(description = "开户行账户")
    private String account;

    /**
     * 主要联系人姓名
     */
    @Schema(description = "主要联系人姓名")
    private String contactPerson;

    /**
     * 手机号
     */
    @Schema(description = "手机号")
    private String phoneNumber;

    /**
     * 状态（0 编辑 1 已签订 2 执行中 3 变更中 4 结算完成 5 归档）
     */
    @Schema(description = "状态（0 编辑 1 已签订 2 执行中 3 变更中 4 结算完成 5 归档）")
    private String status;

    /**
     * 工厂编码
     */
    @Schema(description = "工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

    @ExcelIgnore
    @TableField(exist = false)
    @EntityMapping(thisField = "id", joinField = "contractId")
    private List<PmsContractDetailEntity> pmsContractDetailList;
}