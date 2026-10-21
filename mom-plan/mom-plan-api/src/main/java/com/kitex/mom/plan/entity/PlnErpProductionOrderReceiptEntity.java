package com.kitex.mom.plan.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/** ERP 生产订单映射的永久幂等回执与合同升级证据。 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("pln_erp_production_order_receipt")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "ERP生产订单映射回执")
public class PlnErpProductionOrderReceiptEntity extends BaseEntity<PlnErpProductionOrderReceiptEntity> {
    private String requestId;
    private String requestFingerprint;
    private String sourcePostingRequestId;
    private String erpSystemCode;
    private String externalFactoryCode;
    private String contractNo;
    private Integer sourceContractVersion;
    private Integer mappedContractVersion;
    private String receiptNo;
    @JsonIgnore
    private String normalizedPayloadJson;
    private String status;
    private Long planningPolicyId;
    private Integer planningPolicyVersion;
    private String planningPolicyFingerprint;
    private LocalDateTime receivedTime;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
