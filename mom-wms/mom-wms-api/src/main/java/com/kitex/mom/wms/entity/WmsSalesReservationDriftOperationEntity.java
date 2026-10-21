package com.kitex.mom.wms.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 漂移待办的不可变检测、提审、审批、驳回和恢复关闭履历。
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("wms_sales_reservation_drift_operation")
@EqualsAndHashCode(callSuper = true)
public class WmsSalesReservationDriftOperationEntity extends BaseEntity<WmsSalesReservationDriftOperationEntity> {
    private Long caseId;
    private Long reservationId;
    private String requestId;
    private String requestFingerprint;
    private String operationType;
    private String beforeStatus;
    private String afterStatus;
    private String evidenceFingerprint;
    private String content;
    private String operatorCode;
    private String operatorName;
    private LocalDateTime operationTime;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
