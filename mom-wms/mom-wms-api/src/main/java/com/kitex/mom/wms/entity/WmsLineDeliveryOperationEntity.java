package com.kitex.mom.wms.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 线边配送永久幂等操作履历。
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("wms_line_delivery_operation")
@EqualsAndHashCode(callSuper = true)
public class WmsLineDeliveryOperationEntity extends BaseEntity<WmsLineDeliveryOperationEntity> {
    private Long taskId;
    private Integer sequenceNo;
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
}
