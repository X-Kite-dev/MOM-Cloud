package com.kitex.mom.plan.dto;

import com.kitex.mom.plan.entity.PlnAutomationEventEntity;
import com.kitex.mom.plan.entity.PlnAutomationEventOperationEntity;
import com.kitex.mom.plan.entity.PlnAutomationEventPolicyEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 制造自动化事件收件、策略和人工处置接口模型。
 */
public final class PlanAutomationEventDTO {

    private PlanAutomationEventDTO() {
    }

    @Data
    @Schema(description = "自动化事件查询条件")
    public static class EventQuery {
        @Schema(description = "平台事件编号")
        private String eventNo;
        @Schema(description = "事件来源系统")
        private String sourceSystem;
        @Schema(description = "事件类型")
        private String eventType;
        @Schema(description = "来源业务对象标识")
        private String aggregateId;
        @Schema(description = "处理状态")
        private String status;
        @Schema(description = "匹配的处理器编码")
        private String handlerCode;
    }

    @Data
    @Schema(description = "制造自动化事件接收命令")
    public static class ReceiveCommand {
        @Schema(description = "来源系统事件唯一标识，与来源系统共同构成幂等键")
        private String eventId;
        @Schema(description = "事件来源系统")
        private String sourceSystem;
        @Schema(description = "事件类型")
        private String eventType;
        @Schema(description = "事件结构版本")
        private Integer eventVersion;
        @Schema(description = "来源业务对象类型")
        private String aggregateType;
        @Schema(description = "来源业务对象标识")
        private String aggregateId;
        @Schema(description = "事件所属工厂编码")
        private String factoryCode;
        @Schema(description = "事件在来源系统的实际发生时刻")
        private LocalDateTime occurredTime;
        @Schema(description = "处理优先级，数值越小优先级越高")
        private Integer priority;
        @Schema(description = "事件原始载荷（JSON）")
        private String payloadJson;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "自动化事件接收回执")
    public static class ReceiveReceipt {
        @Schema(description = "事件收件箱主键")
        private Long inboxId;
        @Schema(description = "平台事件编号")
        private String eventNo;
        @Schema(description = "来源系统事件唯一标识")
        private String eventId;
        @Schema(description = "当前处理状态")
        private String status;
        @Schema(description = "是否为幂等重复接收")
        private Boolean duplicate;
        @Schema(description = "平台首次接收时刻")
        private LocalDateTime receivedTime;
    }

    @Data
    @Schema(description = "自动化事件策略查询条件")
    public static class PolicyQuery {
        private String policyCode;
        private String eventType;
        private String handlerCode;
        private String status;
    }

    @Data
    @Schema(description = "自动化事件策略草稿命令")
    public static class PolicyDraftCommand {
        private Long id;
        private String policyCode;
        private String policyName;
        private String factoryCode;
        private String eventType;
        private String handlerCode;
        @Schema(description = "是否允许策略匹配后自动执行；关闭时进入人工确认")
        private Boolean autoExecute;
        @Schema(description = "最大处理尝试次数")
        private Integer maxAttempts;
        @Schema(description = "首次失败后的退避秒数")
        private Integer initialBackoffSeconds;
        @Schema(description = "指数退避的最大秒数")
        private Integer maxBackoffSeconds;
        private Integer priority;
        private LocalDateTime effectiveFrom;
        private LocalDateTime effectiveTo;
    }

    @Data
    @Schema(description = "自动化事件策略状态变更命令")
    public static class PolicyActionCommand {
        private Long policyId;
        private String requestId;
        private String reason;
    }

    @Data
    @Schema(description = "自动化事件人工重试或忽略命令")
    public static class ManualActionCommand {
        private Long eventInboxId;
        private String requestId;
        private String reason;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    @Schema(description = "自动化事件详情及处理履历")
    public static class EventDetail extends PlnAutomationEventEntity {
        @Schema(description = "事件接收时固化的匹配策略")
        private PlnAutomationEventPolicyEntity policy;
        @Schema(description = "事件处理、重试和人工操作履历")
        private List<PlnAutomationEventOperationEntity> operations;
    }
}
