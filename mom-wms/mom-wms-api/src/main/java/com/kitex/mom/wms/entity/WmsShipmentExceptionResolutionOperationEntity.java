package com.kitex.mom.wms.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.activerecord.Model;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 异常处置状态迁移与执行证据的永久幂等履历。
 */
@Data
@Accessors(chain = true)
@TableName("wms_shipment_exception_resolution_operation")
@EqualsAndHashCode(callSuper = true)
public class WmsShipmentExceptionResolutionOperationEntity extends Model<WmsShipmentExceptionResolutionOperationEntity> {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long tenantId;
    private Long caseId;
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
    private String factoryCode;
    @TableField(fill = FieldFill.INSERT)
    private String createBy;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableLogic
    private String delFlag;
}
