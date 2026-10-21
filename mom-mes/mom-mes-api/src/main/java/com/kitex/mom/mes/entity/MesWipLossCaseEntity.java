package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TenantTable
@TableName("mes_wip_loss_case")
@EqualsAndHashCode(callSuper = true)
public class MesWipLossCaseEntity extends BaseEntity<MesWipLossCaseEntity> {
    private String factoryCode;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long transferId;
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal quantity;
    private String status;
    private String investigationEvidence;
    private String responsibility;
    private String prevention;
    private String reason;
    private String basisFingerprint;
    private String sourceSnapshot;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long applicantId;
    private String applicantCode;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long reviewerId;
    private String reviewerCode;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long executorId;
    private String executorCode;
    private LocalDateTime reviewTime;
    private LocalDateTime executeTime;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long lastOperationId;

    @Override
    @JsonSerialize(using = ToStringSerializer.class)
    public Long getId() {
        return super.getId();
    }
}
