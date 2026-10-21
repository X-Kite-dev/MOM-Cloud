package com.kitex.mom.oms.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/** 履约风险不可变操作履历；caseId为空时表示一次交付行风险同步批次。 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("oms_fulfillment_risk_operation")
@EqualsAndHashCode(callSuper = true)
public class OmsFulfillmentRiskOperationEntity extends BaseEntity<OmsFulfillmentRiskOperationEntity> {
    private Long caseId;
    private Long orderDetailId;
    private String requestId;
    private String requestFingerprint;
    private String operationType;
    private String beforeStatus;
    private String afterStatus;
    private String content;
    private String operatorCode;
    private String operatorName;
    private LocalDateTime operationTime;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
