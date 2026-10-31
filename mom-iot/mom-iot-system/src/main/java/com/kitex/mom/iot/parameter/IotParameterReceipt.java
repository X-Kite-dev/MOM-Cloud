package com.kitex.mom.iot.parameter;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/** 接收、执行、读回各自保存原件和指纹；模拟来源不能用于真实开工放行。 */
@Data
@Accessors(chain = true)
public class IotParameterReceipt {
    private Long tenantId;
    private String factoryCode;
    private String requestId;
    private String stage;
    private String factJson;
    private String factHash;
    private LocalDateTime receivedAt;
}
