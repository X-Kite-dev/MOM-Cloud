package com.kitex.mom.plan.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 智能 APS 案件的求解、A2 选案和 A3 发布契约。
 *
 * <p>浏览器只提交业务参数、永久幂等号和上次读取的证据指纹。工厂、资源快照、情景及候选归属
 * 均由服务端依据案件重新装配，禁止客户端直接声明案件状态或发布结果。</p>
 */
public final class PlnApsDecisionDTO {

    private PlnApsDecisionDTO() {
    }

    /** 排产预检或正式求解命令。 */
    @Data
    @Schema(description = "智能APS案件求解命令")
    public static class RunCommand {
        @Schema(description = "永久幂等请求号，同号异载荷拒绝")
        private String requestId;
        @Schema(description = "调用方上次读取的案件上下文SHA-256")
        private String expectedCaseFingerprint;
        @Schema(description = "排产基准日期")
        private LocalDate scheduleDate;
        @Schema(description = "DAY、WEEK、MONTH或TN")
        private String cycleType;
        @Schema(description = "TN周期天数或来源计划数量上限")
        private Integer planNum;
        @Schema(description = "明确限定的生产计划主键，字符串用于避免浏览器精度丢失")
        private List<String> targetIds;
        @Schema(description = "2至5个候选目标，不传时使用现有APS标准候选")
        private List<PlnSchedulingCandidateProfileDTO> candidateProfiles;
        @Schema(description = "已批准目标模板主键，字符串用于避免浏览器精度丢失")
        private String objectiveTemplateId;
        @Schema(description = "目标模板内容指纹")
        private String expectedObjectiveTemplateFingerprint;
    }

    /** A2 人工选案命令。 */
    @Data
    @Schema(description = "智能APS案件A2选案命令")
    public static class SelectCommand {
        private String requestId;
        private String expectedCaseFingerprint;
        @Schema(description = "候选排程任务主键，字符串用于避免浏览器精度丢失")
        private String candidateTaskId;
        private String expectedScenarioFingerprint;
        private String expectedCandidateFingerprint;
        @Schema(description = "计划员选择该候选的业务依据")
        private String reason;
    }

    /** 专业调整命令：从已评估候选派生新版本，不修改父候选或正式计划。 */
    @Data
    @Schema(description = "智能APS专业调整命令")
    public static class DeriveCommand {
        private String requestId;
        private String expectedCaseFingerprint;
        private String expectedScenarioFingerprint;
        private String expectedCandidateFingerprint;
        @Schema(description = "父候选任务主键，使用字符串避免浏览器整数精度丢失")
        private String parentCandidateTaskId;
        @Schema(description = "调整原因，随派生候选永久保存")
        private String reason;
        private List<OperationAdjustment> operations;
    }

    /** 同一请求可以同时移动多道工序；资源序号只能指向父候选冻结的可选资源。 */
    @Data
    @Schema(description = "候选工序调整项")
    public static class OperationAdjustment {
        @Schema(description = "父候选工序主键")
        private String operationId;
        @Schema(description = "新开工时刻；不改变该工序原有效工时")
        private LocalDateTime scheduledStartTime;
        @Schema(description = "父候选资源快照中的可选资源下标；不传表示保留原资源")
        private Integer resourceChoiceIndex;
    }

    /** A3 异岗审批后的正式发布命令。 */
    @Data
    @Schema(description = "智能APS案件A3发布命令")
    public static class PublishCommand {
        private String requestId;
        private String expectedCaseFingerprint;
        private String expectedScenarioFingerprint;
        private String expectedSelectionFingerprint;
        @Schema(description = "PLAN A3 审批主键；审批身份和编号由服务端读取")
        private String approvalId;
    }

    /** A2 选案后的 A3 送审命令。 */
    @Data
    @Schema(description = "APS A3 送审命令")
    public static class ApprovalSubmitCommand {
        private String requestId;
        private String expectedCaseFingerprint;
        private String expectedScenarioFingerprint;
        private String expectedSelectionFingerprint;
        private String reason;
    }

    /** 异岗审核人对当前冻结方案的决定。 */
    @Data
    @Schema(description = "APS A3 审核命令")
    public static class ApprovalDecisionCommand {
        private String requestId;
        private String approvalId;
        private Boolean approved;
        private String reason;
    }

    /** 工作台展示的真实 A3 审批摘要。 */
    @Data
    @Accessors(chain = true)
    @Schema(description = "APS A3 审批摘要")
    public static class Approval {
        private String id;
        private String approvalNo;
        private String status;
        private String applicantCode;
        private String approverCode;
        private String submitReason;
        private String decisionReason;
        private String caseFingerprint;
        private String scenarioFingerprint;
        private String selectionFingerprint;
        private String publishRequestId;
        private LocalDateTime submittedTime;
        private LocalDateTime decidedTime;
    }

    /** 案件求解前门禁与现有 APS 预检的组合结果。 */
    @Data
    @Accessors(chain = true)
    @Schema(description = "智能APS案件求解预检")
    public static class Preview {
        private PlnApsCaseDTO apsCase;
        private PlnApsResourceDTO.Snapshot resourceSnapshot;
        private PlanSchedulingAgentDTO.Readiness solverReadiness;
        private Boolean ready;
        private List<String> blockers;
    }

    /** 案件与多候选证据的同屏比较结果。 */
    @Data
    @Accessors(chain = true)
    @Schema(description = "智能APS案件候选比较")
    public static class Comparison {
        private PlnApsCaseDTO apsCase;
        private PlanSchedulingAgentDTO.DecisionContext decisionContext;
    }

    /** 正式发布回执与案件新状态。 */
    @Data
    @Accessors(chain = true)
    @Schema(description = "智能APS案件正式发布回执")
    public static class PublishResult {
        private PlnApsCaseDTO apsCase;
        private PlanSchedulingAgentDTO.PublishReceipt receipt;
    }
}
