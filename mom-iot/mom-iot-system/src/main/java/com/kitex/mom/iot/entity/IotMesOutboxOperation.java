package com.kitex.mom.iot.entity;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;
@Data @EqualsAndHashCode(callSuper=true) @TenantTable @TableName("iot_mes_outbox_operation")
public class IotMesOutboxOperation extends BaseEntity<IotMesOutboxOperation> {
    private Long outboxId;
    private String requestId;
    private String action;
    private String reason;
    private Long operatorId;
    private String resultStatus;
    private String receiptJson;
    private String factoryCode;
    private java.time.LocalDateTime operationTime;
}
