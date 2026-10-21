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
@TableName("mes_wip_transfer_operation")
@EqualsAndHashCode(callSuper = true)
public class MesWipTransferOperationEntity extends BaseEntity<MesWipTransferOperationEntity> {
    private String factoryCode;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long transferId;
    private String requestId;
    private String fingerprint;
    private String operationType;
    private Long sequenceNo;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long operatorId;
    private String operatorCode;
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal quantity;
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal beforeRemaining;
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal afterRemaining;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long resultStockId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long qualityCaseId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long lossCaseId;
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal sourceBeforeNum;
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal sourceAfterNum;
    private String beforeStatus;
    private String afterStatus;
    private String locationCode;
    private String reason;
    private String evidence;

    @Override
    @JsonSerialize(using = ToStringSerializer.class)
    public Long getId() {
        return super.getId();
    }
}
