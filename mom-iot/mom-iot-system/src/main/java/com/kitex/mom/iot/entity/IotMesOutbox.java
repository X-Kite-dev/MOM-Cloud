package com.kitex.mom.iot.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

@Data @EqualsAndHashCode(callSuper=true) @TenantTable @TableName("iot_mes_outbox")
public class IotMesOutbox extends BaseEntity<IotMesOutbox> {
    private Long sourceId;
    private Long deviceId;
    private String factoryCode;
    private String sourceEventId;
    private String payloadJson;
    private String fingerprint;
    private String status;
    private Integer attemptCount;
    private Integer cycleAttempts;
    @TableField(updateStrategy=FieldStrategy.ALWAYS) private LocalDateTime nextAttemptTime;
    @TableField(updateStrategy=FieldStrategy.ALWAYS) private String leaseToken;
    @TableField(updateStrategy=FieldStrategy.ALWAYS) private LocalDateTime leaseUntil;
    @TableField(updateStrategy=FieldStrategy.ALWAYS) private String lastError;
    private String receiptJson;
    private LocalDateTime deliveredTime;
}
