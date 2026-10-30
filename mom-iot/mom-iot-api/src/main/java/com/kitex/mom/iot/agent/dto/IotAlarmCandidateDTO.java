package com.kitex.mom.iot.agent.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.util.List;

/** 参数异常调查选择原 IoT 告警时使用的有界只读目录合同。 */
public final class IotAlarmCandidateDTO {

    private IotAlarmCandidateDTO() {
    }

    @Data
    @Accessors(chain = true)
    public static class Query {
        /** 必须等于认证上下文中的实际工厂。 */
        @NotBlank
        private String factoryCode;
        /** 设备编码、参数标识或告警名称的有界搜索词。 */
        private String keyword;
        /** 从 1 开始的页码。 */
        private Integer current;
        /** 单页最多 20 条。 */
        private Integer size;
    }

    @Data
    @Accessors(chain = true)
    public static class Candidate {
        /** 主键以字符串返回，避免浏览器对雪花 ID 做浮点截断。 */
        private String alarmEpisodeId;
        /** 原告警绑定的设备编码。 */
        private String deviceCode;
        /** 原告警规则监控的采集参数标识。 */
        private String parameterIdentifier;
        /** 原告警显示名称。 */
        private String parameterName;
        /** 原告警等级。 */
        private String severity;
        /** 原告警状态；不是本 Agent 的处置状态。 */
        private String status;
        /** 首次越界时间，旧数据缺失时回退到触发时间。 */
        private LocalDateTime occurredAt;
        /** 只表示候选来源字段齐全，不表示已匹配 MES 工单。 */
        private Boolean selectable;
        /** 不能选取时的字段缺口。 */
        private String unavailableReason;
    }

    @Data
    @Accessors(chain = true)
    public static class CandidatePage {
        /** 用于调用方再次核对租户隔离的字符串主键。 */
        private String tenantId;
        /** 当前实际工厂。 */
        private String factoryCode;
        /** 当前页码。 */
        private Integer current;
        /** 单页条数。 */
        private Integer size;
        /** 同范围、同筛选条件下的总条数。 */
        private Long total;
        /** 当前页的告警摘要。 */
        private List<Candidate> records;
    }
}
