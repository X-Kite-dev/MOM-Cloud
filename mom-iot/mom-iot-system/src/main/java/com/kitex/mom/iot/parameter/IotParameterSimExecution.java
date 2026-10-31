package com.kitex.mom.iot.parameter;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/** 模拟设备侧独立提交的原号去重日志，业务端丢响应不撤销该事实。 */
@Data
@Accessors(chain = true)
public class IotParameterSimExecution {
    private Long tenantId;
    private String factoryCode;
    private String requestId;
    private String commandHash;
    private String deviceCode;
    private String firmwareVersion;
    private String outcome;
    private Integer executionCount;
    private String valuesJson;
    private LocalDateTime receivedAt;
    private LocalDateTime executedAt;
    private LocalDateTime readBackAt;
}
