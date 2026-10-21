package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

@Data
@TenantTable
@TableName("mes_case_disposition_receipt")
@EqualsAndHashCode(callSuper = true)
public class MesCaseDispositionReceiptEntity extends BaseEntity<MesCaseDispositionReceiptEntity> {
    private String factoryCode;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long caseId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long detailId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long ncrId;
    private String decisionKey;
    private String decisionSnapshot;
    private String commandSnapshot;
    private String requestId;
    private String fingerprint;
    private String stage;
    private String action;
    private Long sequenceNo;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long executionReceiptId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long previousStockReceiptId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long resultStockId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long operatorId;
    private String operatorCode;
    private String deciderCode;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long deciderId;
    private String evidence;
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal quantity;
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal beforeQuantity;
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal afterQuantity;

    @Override
    @JsonSerialize(using = ToStringSerializer.class)
    public Long getId() {
        return super.getId();
    }
}
