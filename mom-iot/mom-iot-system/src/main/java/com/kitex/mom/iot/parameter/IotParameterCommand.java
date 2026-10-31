package com.kitex.mom.iot.parameter;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/** 永久命令原件；XML仅更新执行状态和时间，不使用可覆盖整行的通用updateById。 */
@Data
@Accessors(chain = true)
public class IotParameterCommand {
    private Long tenantId;
    private String factoryCode;
    private String requestId;
    private Long actorId;
    private String requestHash;
    private String requestJson;
    private String contextHash;
    private String contextJson;
    private String taskCode;
    private String arrangeCode;
    private Long deviceId;
    private String deviceCode;
    private String firmwareVersion;
    private String deviceSnapshot;
    private String mode;
    private String scenario;
    private String state;
    private LocalDateTime createdAt;
    private LocalDateTime requestedAt;
    private LocalDateTime updatedAt;
}
