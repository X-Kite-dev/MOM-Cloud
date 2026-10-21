package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 聚合报工差异结案的不可变操作履历和幂等回执。
 */
@Data
@TenantTable
@Accessors(chain = true)
@TableName("mes_report_correction_settlement_operation")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "聚合报工差异结案不可变履历")
public class MesReportCorrectionSettlementOperationEntity
        extends BaseEntity<MesReportCorrectionSettlementOperationEntity> {

    private Long settlementId;
    private String requestId;
    private String requestFingerprint;
    @Schema(description = "SUBMIT/APPROVE/REJECT/WITHDRAW/MARK_STALE")
    private String operationType;
    private String beforeStatus;
    private String afterStatus;
    private String content;
    private Long operatorId;
    private String operatorCode;
    private String operatorName;
    private LocalDateTime operationTime;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
