package com.kitex.mom.wms.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TenantTable
@TableName("wms_material_reservation")
@EqualsAndHashCode(callSuper = true)
public class WmsMaterialReservationEntity extends BaseEntity<WmsMaterialReservationEntity> {
    private String reservationNo;
    private String requestId;
    private String sourceType;
    private Long sourceId;
    private String sourceNo;
    private String warehouseCode;
    private String warehouseName;
    private String customerCode;
    private LocalDate requiredDate;
    private String status;
    private String allocationSnapshot;
    private String releaseRequestId;
    private String releaseReason;
    private String releaseBy;
    private LocalDateTime releaseTime;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
