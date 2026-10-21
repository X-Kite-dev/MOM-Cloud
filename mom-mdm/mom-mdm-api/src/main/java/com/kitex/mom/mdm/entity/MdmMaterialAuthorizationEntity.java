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
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TenantTable
@TableName("mdm_material_authorization")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "替代料供应商认证/客户批准权威台账")
public class MdmMaterialAuthorizationEntity extends BaseEntity<MdmMaterialAuthorizationEntity> {

    @Schema(description = "授权类型：SUPPLIER_CERTIFICATION、CUSTOMER_APPROVAL")
    private String authorizationType;
    @Schema(description = "供应商或客户编码")
    private String partnerCode;
    @Schema(description = "供应商或客户名称")
    private String partnerName;
    @Schema(description = "获授权物料编码")
    private String materialCode;
    @Schema(description = "获授权物料名称")
    private String materialName;
    @Schema(description = "认证/批准编号")
    private String authorizationNo;
    @Schema(description = "有效开始日期")
    private LocalDate validStart;
    @Schema(description = "有效截止日期")
    private LocalDate validEnd;
    @Schema(description = "状态：DRAFT、ACTIVE、SUSPENDED、REVOKED")
    private String status;
    @Schema(description = "检验等级：NORMAL、TIGHTENED，仅供应商认证使用")
    private String inspectionLevel;
    @Schema(description = "加严检验指定质量标准")
    private Long qualityStandardId;
    @Schema(description = "加严检验指定质量标准名称")
    private String qualityStandardName;
    @Schema(description = "参考不含税单价")
    private BigDecimal referenceUnitPrice;
    @Schema(description = "币种")
    private String currency;
    @Schema(description = "认证/批准附件")
    private String attachment;
    @Schema(description = "备注")
    private String remark;
    @Schema(description = "启用人编码")
    private String approvedBy;
    @Schema(description = "启用人姓名")
    private String approvedByName;
    @Schema(description = "启用时间")
    private LocalDateTime approvedTime;
    @Schema(description = "暂停/撤销原因")
    private String closeReason;
    @Schema(description = "暂停/撤销人编码")
    private String closedBy;
    @Schema(description = "暂停/撤销人姓名")
    private String closedByName;
    @Schema(description = "暂停/撤销时间")
    private LocalDateTime closedTime;
    @Schema(description = "工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

    @TableField(exist = false)
    @Schema(description = "时效状态：NOT_STARTED、VALID、EXPIRING、EXPIRED、CLOSED")
    private String validityState;
    @TableField(exist = false)
    @Schema(description = "距到期天数，负数表示已过期")
    private Long daysToExpire;
}
