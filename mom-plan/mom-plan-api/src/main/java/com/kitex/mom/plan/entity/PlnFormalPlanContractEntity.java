package com.kitex.mom.plan.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * ERP 与 MOM 两种来源统一发布的不可变正式计划合同明细。
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("pln_formal_plan_contract")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "正式计划合同明细")
public class PlnFormalPlanContractEntity extends BaseEntity<PlnFormalPlanContractEntity> {
    private String contractNo;
    private Integer contractVersion;
    private String lineNo;
    private String parentLineNo;
    private String sourceSystemCode;
    private String sourceFactoryCode;
    private String sourceDemandNo;
    private String sourcePlanNo;
    private String sourcePlanVersion;
    @Schema(description = "供应类型：MAKE、BUY、SUBCONTRACT")
    private String supplyType;
    @Schema(description = "ERP正式生产订单号；MOM自建执行订单模式允许为空")
    private String erpProductionOrderNo;
    private String productCode;
    private String productName;
    private BigDecimal plannedQuantity;
    private String unitCode;
    private LocalDate demandDate;
    private LocalDateTime committedStartTime;
    private LocalDateTime committedEndTime;
    private String bomVersion;
    private String routeVersion;
    private String substitutePolicyVersion;
    private Long planningPolicyId;
    private Integer planningPolicyVersion;
    private String planningPolicyFingerprint;
    private String sourceContentFingerprint;
    private String contentFingerprint;
    private String requestId;
    private String verificationStatus;
    private LocalDateTime verifiedTime;
    private String contractStatus;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
