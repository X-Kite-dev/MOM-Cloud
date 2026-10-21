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
 * 物流保管链不可变操作履历。
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("wms_shipment_custody_operation")
@EqualsAndHashCode(callSuper = true)
public class WmsShipmentCustodyOperationEntity extends BaseEntity<WmsShipmentCustodyOperationEntity> {
    private Long custodyId;
    private String requestId;
    private String operationType;
    private String beforeState;
    private String afterState;
    private String operatorCode;
    private String operatorName;
    private LocalDateTime operatedTime;
    private String reason;
    private String evidence;
    private String factSnapshot;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
