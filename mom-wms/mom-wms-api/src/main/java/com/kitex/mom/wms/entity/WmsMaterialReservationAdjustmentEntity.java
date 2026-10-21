package com.kitex.mom.wms.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@TenantTable
@TableName("wms_material_reservation_adjustment")
@EqualsAndHashCode(callSuper = true)
public class WmsMaterialReservationAdjustmentEntity
        extends BaseEntity<WmsMaterialReservationAdjustmentEntity> {
    private Long reservationId;
    private String reservationNo;
    private String requestId;
    private String adjustmentType;
    private String reason;
    private String status;
    private String riskSnapshot;
    private String resultSnapshot;
    private String operatedBy;
    private String operatedByName;
    private LocalDateTime operatedTime;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
