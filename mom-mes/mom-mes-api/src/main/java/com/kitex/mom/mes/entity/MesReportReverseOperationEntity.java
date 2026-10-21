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
 * 报工冲销的不可变操作与幂等回执。
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("mes_report_reverse_operation")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "MES报工冲销不可变履历")
public class MesReportReverseOperationEntity extends BaseEntity<MesReportReverseOperationEntity> {
    private Long reverseRequestId;
    private String requestId;
    private String requestFingerprint;
    @Schema(description = "SUBMIT/APPROVE/REJECT/WITHDRAW/MARK_STALE/PERMIT_EXPIRE/PERMIT_CANCEL")
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
