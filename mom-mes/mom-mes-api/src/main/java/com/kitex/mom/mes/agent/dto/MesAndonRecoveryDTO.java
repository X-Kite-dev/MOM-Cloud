package com.kitex.mom.mes.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 生产异常安灯处置Agent使用的MES权威只读合同。
 *
 * <p>安灯事件主键是唯一调查入口。事件处理、发起人验证和独立复产审批仍由MES原状态机办理，
 * 本合同只为模型调查和处置后效果复核提供有界、可指纹化的事实。</p>
 */
public final class MesAndonRecoveryDTO {

    private MesAndonRecoveryDTO() {
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "生产异常安灯精确查询")
    public static class Query {
        @NotBlank
        @Size(max = 100)
        private String factoryCode;

        @NotBlank
        @Size(max = 19)
        private String recordId;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "按Agent转交幂等号查找真实安灯回执")
    public static class ReceiptQuery {
        @NotBlank
        @Size(max = 100)
        private String factoryCode;

        @NotBlank
        @Size(max = 64)
        private String requestId;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "安灯转交回执；未提交时found=false，不推测业务状态")
    public static class Receipt {
        private boolean found;
        private Evidence evidence;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "生产异常处置与复产控制权威证据")
    public static class Evidence {
        private String toolCode;
        private String metricDefinition;
        private Long tenantId;
        private String factoryCode;
        private String recordId;
        private String requestId;
        private String recordCode;
        private String typeCode;
        private String typeName;
        private String level;
        private String state;
        private String exceptionDescription;
        private Boolean impactProduction;
        private Boolean productionPaused;
        private String productionPauseResult;
        private String taskCode;
        private String produceTaskCode;
        private String arrangeCode;
        private String deviceCode;
        private String worksiteCode;
        private String handlerName;
        private String receiverName;
        private String rootCause;
        private String correctiveAction;
        private String handleResult;
        private LocalDateTime responseDeadline;
        private LocalDateTime handleDeadline;
        private Integer escalationCount;
        private LocalDateTime receiveTime;
        private LocalDateTime handleTime;
        private LocalDateTime closeTime;
        private String verificationResult;
        private String verificationRemark;
        private String verifierName;
        private LocalDateTime verificationTime;
        private String cancelReason;
        private Boolean eventTerminal;
        private Boolean productionReleaseRequired;
        private Boolean productionReleaseAllowed;
        private Boolean recoveryVerified;
        private String arrangeStatus;
        private ReleaseSnapshot latestRelease;
        private List<OperationSnapshot> operations = new ArrayList<>();
        private Boolean operationsTruncated;
        private LocalDateTime observedAt;
        private LocalDateTime createTime;
        private List<String> dataGaps = new ArrayList<>();
        private List<String> limitations = new ArrayList<>();
        private List<String> evidenceRefs = new ArrayList<>();
        private String factFingerprint;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "安灯不可变操作履历摘要")
    public static class OperationSnapshot {
        private String operationId;
        private String operationType;
        private String beforeState;
        private String afterState;
        private String content;
        private String operatorCode;
        private String operatorName;
        private LocalDateTime operationTime;
        private String pauseOutcome;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "当前最新复产申请或审批摘要")
    public static class ReleaseSnapshot {
        private String releaseId;
        private String action;
        private String operatorName;
        private String reason;
        private LocalDateTime operationTime;
        private String evidenceFingerprint;
    }
}
