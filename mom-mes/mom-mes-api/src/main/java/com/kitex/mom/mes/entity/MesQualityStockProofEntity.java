package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 质量状态转换及拆分证据，不计作物流入出库。只追加，不回填历史。
 */
@Data
@TenantTable
@TableName("mes_quality_stock_proof")
@EqualsAndHashCode(callSuper = true)
public class MesQualityStockProofEntity extends BaseEntity<MesQualityStockProofEntity> {
    private String factoryCode;
    private String proofVersion;
    private String produceTaskCode;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long sourceReportId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long sourceStockId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long resultStockId;
    private String operationType;
    private String matCode;
    private String matBatch;
    private String matBarcode;
    private String matUnit;
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal quantity;
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal sourceBeforeNum;
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal sourceAfterNum;
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal resultAfterNum;
    private String beforeInspectionStatus;
    private String afterInspectionStatus;
    private String beforeLockStatus;
    private String afterLockStatus;
    private String decisionSnapshot;
    private String sourceSnapshot;

    @Override
    @JsonSerialize(using = ToStringSerializer.class)
    public Long getId() {
        return super.getId();
    }
}
