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
@TableName("mes_wip_transfer")
@EqualsAndHashCode(callSuper = true)
public class MesWipTransferEntity extends BaseEntity<MesWipTransferEntity> {
    private String factoryCode;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long sourceStockId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long sourceReportId;
    private String arrangeCode;
    private String produceTaskCode;
    private String matCode;
    private String matName;
    private String matBatch;
    private String matBarcode;
    private String matUnit;
    private String reportMode;
    private Boolean indivisible;
    private String fromLocationCode;
    private String fromLocationName;
    private String toLocationCode;
    private String toLocationName;
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal quantity;
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal receivedQuantity;
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal returnedQuantity;
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal quarantinedQuantity;
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal lostQuantity;
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal remainingQuantity;
    private String status;
    private Long revision;
    private String resumeStatus;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long dispatchUserId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long returnUserId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long holdUserId;
    private LocalDateTime dispatchTime;
    private LocalDateTime dueTime;
    private LocalDateTime closedTime;
    private String reason;
    private String sourceSnapshot;
    private String locationSnapshot;

    @Override
    @JsonSerialize(using = ToStringSerializer.class)
    public Long getId() {
        return super.getId();
    }
}
