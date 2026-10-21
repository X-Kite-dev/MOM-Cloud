package com.kitex.mom.iot.entity;
import com.baomidou.mybatisplus.annotation.*;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
@TenantTable
@TableName("iot_alarm_operation")
public class IotAlarmOperation extends BaseEntity<IotAlarmOperation> {
    private Long ruleId;
    private Long alarmId;
    private String requestId;
    private String fingerprint;
    private String action;
    private String reason;
    private Long operatorId;
    private String resultStatus;
    private LocalDateTime operationTime;
    private String factoryCode;
}

