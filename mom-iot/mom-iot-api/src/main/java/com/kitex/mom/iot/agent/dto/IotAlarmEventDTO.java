package com.kitex.mom.iot.agent.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.util.List;

/** IoT 原告警事件的有界增量读取合同；只提供已持久化的告警身份。 */
public final class IotAlarmEventDTO {

    private IotAlarmEventDTO() {
    }

    @Data
    @Accessors(chain = true)
    public static class Query {
        /** 必须与认证上下文的实际工厂一致。 */
        @NotBlank
        private String factoryCode;
        /** 严格大于此原告警主键，零表示从订阅创建时间开始。 */
        private Long afterAlarmId;
        /** 订阅创建时间；不回放订阅以前的历史告警。 */
        private LocalDateTime notBefore;
        /** 单次最多读取 50 条。 */
        private Integer limit;
    }

    @Data
    @Accessors(chain = true)
    public static class AlarmEvent {
        /** 字符串保留雪花主键精度。 */
        private String alarmEpisodeId;
        private LocalDateTime triggeredTime;
    }

    @Data
    @Accessors(chain = true)
    public static class Batch {
        private String tenantId;
        private String factoryCode;
        private List<AlarmEvent> records;
    }
}
