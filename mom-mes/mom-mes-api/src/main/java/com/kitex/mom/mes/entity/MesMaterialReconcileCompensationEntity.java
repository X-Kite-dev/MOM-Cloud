package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@TenantTable
@TableName("mes_material_reconcile_compensation")
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@Schema(description = "物料跨模块对账受控补偿记录")
public class MesMaterialReconcileCompensationEntity extends BaseEntity<MesMaterialReconcileCompensationEntity> {
    private Long issueId;
    private String requestId;
    /**
     * AUTOMATIC / MANUAL。
     */
    private String repairMode;
    /**
     * REPLAY_WMS_MES_CALLBACK / MANUAL_SOURCE_REPAIR。
     */
    private String compensationAction;
    /**
     * DRAFT / APPROVED / EXECUTING / SUCCEEDED / FAILED / CANCELLED。
     */
    private String status;
    private String reason;
    private String planSnapshot;
    private String resultSnapshot;
    private String failureMessage;
    private String proposedBy;
    private String proposedByName;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime proposedTime;
    private String approvedBy;
    private String approvedByName;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime approvedTime;
    private String executedBy;
    private String executedByName;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime executedTime;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
