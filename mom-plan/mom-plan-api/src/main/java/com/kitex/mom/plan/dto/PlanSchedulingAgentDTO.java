package com.kitex.mom.plan.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 智能排产 Agent 与 PLAN 确定性 APS 之间的版本化证据契约。
 *
 * <p>Agent 只能生成建议和解释；候选可行性由确定性 APS 校验，正式发布必须经过人工选案、指纹复核和
 * 授权校验。</p>
 */
public final class PlanSchedulingAgentDTO {

    private PlanSchedulingAgentDTO() {
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "智能排产 Agent 运行前预检结果")
    public static class Readiness {
        @Schema(description = "是否具备生成多候选排程的基本条件")
        private Boolean ready;
        @Schema(description = "工厂编码")
        private String factoryCode;
        @Schema(description = "排产日期")
        private LocalDate scheduleDate;
        @Schema(description = "排产周期")
        private String cycleType;
        @Schema(description = "本次纳入的可排计划数量")
        private Integer sourcePlanCount;
        @Schema(description = "计划生成的候选方案数量")
        private Integer candidateCount;
        @Schema(description = "候选方案名称")
        private List<String> candidateNames;
        @Schema(description = "生效排产规则编码")
        private String ruleCode;
        @Schema(description = "生效排产规则版本")
        private Integer ruleVersion;
        @Schema(description = "已批准行业目标模板引用；未引用时为空")
        private String objectiveTemplateVersionRef;
        @Schema(description = "行业目标模板名称")
        private String objectiveTemplateName;
        @Schema(description = "行业标识")
        private String industryCode;
        @Schema(description = "部分来源计划号，用于人工确认运行边界")
        private List<String> sourcePlanNos;
        @Schema(description = "预检结论或阻断原因")
        private String summary;
        @Schema(description = "预检时刻")
        private LocalDateTime capturedTime;
    }

    @Data
    @Schema(description = "排产 Agent 情景查询条件")
    public static class ScenarioQuery {
        @Schema(description = "工厂编码")
        private String factoryCode;
        @Schema(description = "情景主键；与情景编号至少传一个")
        private Long scenarioId;
        @Schema(description = "情景编号；与情景主键至少传一个")
        private String scenarioNo;
    }

    @Data
    @Schema(description = "排产 Agent 候选选择命令")
    public static class SelectCommand {
        @Schema(description = "工厂编码")
        private String factoryCode;
        @Schema(description = "情景编号")
        private String scenarioNo;
        @Schema(description = "选案业务幂等号")
        private String requestId;
        @Schema(description = "被选择的候选排程任务主键")
        private Long candidateTaskId;
        @Schema(description = "查询情景时返回的情景指纹")
        private String expectedScenarioFingerprint;
        @Schema(description = "被选择候选的证据指纹")
        private String expectedCandidateFingerprint;
        @Schema(description = "人工选择原因")
        private String reason;
    }

    @Data
    @Schema(description = "排产 Agent 正式发布命令")
    public static class PublishCommand {
        @Schema(description = "工厂编码")
        private String factoryCode;
        @Schema(description = "情景编号")
        private String scenarioNo;
        @Schema(description = "发布业务幂等号")
        private String requestId;
        @Schema(description = "已选候选任务主键")
        private Long selectedTaskId;
        @Schema(description = "发布前情景指纹")
        private String expectedScenarioFingerprint;
        @Schema(description = "人工选案结论指纹")
        private String expectedSelectionFingerprint;

        /** 由Agent受控执行器在执行时注入，不能由模型自行声明。 */
        @Schema(description = "Agent 受控执行审批主键，由执行器注入")
        private Long agentApprovalId;
        @Schema(description = "Agent 受控执行审批编号，由执行器注入")
        private String agentApprovalNo;
        @Schema(description = "申请人编码")
        private String applicantCode;
        @Schema(description = "审批人编码，必须与申请人分离")
        private String approverCode;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "排产 Agent 情景证据包")
    public static class ScenarioEvidence {
        @Schema(description = "确定性 APS 情景信息")
        private PlnSchedulingScenarioDTO scenario;
        @Schema(description = "情景当前版本指纹")
        private String scenarioFingerprint;
        @Schema(description = "生成情景所依据的需求、资源和执行事实指纹")
        private String sourceFingerprint;
        @Schema(description = "规则集及版本引用")
        private String ruleVersionRef;
        @Schema(description = "求解时冻结的行业目标模板版本；手工候选时为空")
        private String objectiveTemplateVersionRef;
        @Schema(description = "Agent 生成的候选推荐摘要，仅作决策辅助")
        private String recommendationSummary;
        @Schema(description = "面向计划员的结构化决策简报，所有结论均可回溯候选指标快照")
        private DecisionBrief decisionBrief;
        @Schema(description = "满足全部硬约束的候选数量")
        private Integer feasibleCandidateCount;
        @Schema(description = "存在硬约束冲突的候选数量")
        private Integer hardBlockedCandidateCount;
        @Schema(description = "证据包生成时刻")
        private LocalDateTime capturedTime;
        @Schema(description = "候选证据列表")
        private List<CandidateEvidence> candidates;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "计划员、Agent解释和效果复盘共同使用的冻结排产决策上下文")
    public static class DecisionContext {
        @Schema(description = "合同版本")
        private String contractVersion;
        @Schema(description = "同一时刻重采的APS情景、候选与硬约束证据")
        private ScenarioEvidence scenarioEvidence;
        @Schema(description = "正式发布后的MES执行效果；未发布时返回明确不可用原因")
        private EffectEvidence effectEvidence;
        @Schema(description = "工厂计划权威、正式合同与模拟/正式边界")
        private PlanningAuthorityEvidence planningAuthority;
        @Schema(description = "EVALUATING、WAITING_SELECTION、WAITING_APPROVAL、PUBLISHED_OBSERVING、BLOCKED或FAILED")
        private String decisionState;
        @Schema(description = "所有候选共同拥有且可直接比较的指标编码")
        private List<String> commonMetricCodes;
        @Schema(description = "共同指标在每个候选中的原始值；键为候选任务主键字符串")
        private Map<String, Map<String, String>> candidateCommonMetrics;
        @Schema(description = "候选指标是否具有同一分母并可比较")
        private Boolean comparable;
        @Schema(description = "选择状态、执行事实、截断或来源缺失形成的数据缺口")
        private List<String> dataGaps;
        @Schema(description = "权威来源引用")
        private List<String> evidenceRefs;
        @Schema(description = "上下文冻结时刻")
        private LocalDateTime capturedTime;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "排产决策冻结的工厂计划权威证据")
    public static class PlanningAuthorityEvidence {
        private String policyId;
        private Integer policyVersion;
        private String policyFingerprint;
        private String mrpAuthority;
        private String productionOrderSyncMode;
        private String substituteGovernanceMode;
        private String formalPlanContractNo;
        private Integer formalPlanContractVersion;
        private String formalPlanContractFingerprint;
        @Schema(description = "是否具有当前策略下可核验的正式计划合同；历史记录不可判定时为空")
        private Boolean formal;
        @Schema(description = "是否明确为仿真/诊断上下文；历史记录不可判定时为空")
        private Boolean simulation;
        @Schema(description = "CURRENT、STALE_POLICY、CONTRACT_MISSING或HISTORICAL_UNDETERMINED")
        private String evidenceStatus;
        private LocalDateTime observedAt;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "智能排产 Agent 决策简报")
    public static class DecisionBrief {
        @Schema(description = "Agent 推荐候选任务主键；全部候选硬阻断时为空")
        private Long recommendedTaskId;
        @Schema(description = "Agent 推荐候选名称")
        private String recommendedCandidateName;
        @Schema(description = "推荐结论")
        private String conclusion;
        @Schema(description = "相对其他可行候选的主要收益")
        private List<String> gains;
        @Schema(description = "相对单项最优候选需要接受的代价")
        private List<String> tradeoffs;
        @Schema(description = "正式发布前必须关注的风险")
        private List<String> risks;
        @Schema(description = "当前状态对应的下一步动作")
        private String nextStep;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "排产候选证据")
    public static class CandidateEvidence {
        @Schema(description = "候选排程任务主键")
        private Long taskId;
        @Schema(description = "专业调整来源候选任务主键；原始求解候选为空")
        @com.fasterxml.jackson.databind.annotation.JsonSerialize(
                using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class)
        private Long parentCandidateTaskId;
        @Schema(description = "专业调整原因；不替代A2人工选择依据")
        private String derivationReason;
        @Schema(description = "相对父候选的工序差异JSON")
        private String derivationDiffSnapshot;
        @Schema(description = "候选策略编码")
        private String candidateCode;
        @Schema(description = "候选策略名称")
        private String candidateName;
        @Schema(description = "候选采用的排序策略")
        private String sequenceStrategy;
        @Schema(description = "候选排名")
        private Integer rank;
        @Schema(description = "确定性目标函数评分")
        private BigDecimal score;
        @Schema(description = "是否满足全部硬约束")
        private Boolean feasible;
        @Schema(description = "是否为人工选中的候选")
        private Boolean selected;
        @Schema(description = "交期、换型、负荷等评估指标快照（JSON）")
        private String metricsSnapshot;
        @Schema(description = "目标函数权重快照（JSON）")
        private String objectiveWeightsSnapshot;
        @Schema(description = "候选排程与证据综合指纹")
        private String candidateFingerprint;
        @Schema(description = "Agent 对候选取舍的解释，不作为硬约束结论")
        private String tradeoffSummary;
        @Schema(description = "候选涉及的计划总数")
        private Integer impactedPlanCount;
        @Schema(description = "按硬阻断、延期和物料风险排序的重点计划，最多20条")
        private List<PlanImpact> priorityPlanImpacts;
        @Schema(description = "重点计划是否因数量限制被截断")
        private Boolean planImpactTruncated;
        @Schema(description = "按超载、容量缺证和利用率排序的资源日热点，最多12条")
        private List<ResourceHotspot> resourceHotspots;
        @Schema(description = "资源日热点是否因数量限制被截断")
        private Boolean resourceHotspotTruncated;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "候选排程对单个生产计划的影响")
    public static class PlanImpact {
        @Schema(description = "生产计划主键")
        private Long issueId;
        @Schema(description = "生产计划号")
        private String issueNo;
        @Schema(description = "客户名称")
        private String customerName;
        @Schema(description = "产品编码")
        private String productCode;
        @Schema(description = "产品名称")
        private String productName;
        @Schema(description = "需求日期")
        private LocalDate demandDate;
        @Schema(description = "候选首道工序开始时间")
        private LocalDateTime scheduledStartTime;
        @Schema(description = "候选末道工序结束时间")
        private LocalDateTime scheduledEndTime;
        @Schema(description = "相对需求日期的延期天数")
        private Long lateDays;
        @Schema(description = "计划内工序等待分钟")
        private Long waitingMinutes;
        @Schema(description = "排产工序数")
        private Integer operationCount;
        @Schema(description = "物料约束状态")
        private String materialConstraintStatus;
        @Schema(description = "是否存在物料或工序硬阻断")
        private Boolean hardBlocked;
        @Schema(description = "影响与风险摘要")
        private String riskSummary;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "候选排程资源日负荷热点")
    public static class ResourceHotspot {
        @Schema(description = "资源类型")
        private String resourceType;
        @Schema(description = "资源编码")
        private String resourceCode;
        @Schema(description = "资源名称")
        private String resourceName;
        @Schema(description = "负荷日期")
        private LocalDate loadDate;
        @Schema(description = "负荷率百分比")
        private BigDecimal utilizationRate;
        @Schema(description = "计划占用分钟")
        private Long plannedMinutes;
        @Schema(description = "可用分钟")
        private Long availableMinutes;
        @Schema(description = "超载分钟")
        private Long overloadMinutes;
        @Schema(description = "NORMAL、HIGH、SATURATED或OVERLOAD")
        private String loadStatus;
        @Schema(description = "容量是否经过日历和资源快照完整核验")
        private Boolean capacityVerified;
        @Schema(description = "容量口径说明")
        private String capacityExplanation;
        @Schema(description = "该资源日涉及工序数")
        private Integer operationCount;
        @Schema(description = "该资源日涉及计划数")
        private Integer issueCount;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "智能排产发布后的MES执行效果证据")
    public static class EffectEvidence {
        @Schema(description = "是否已形成可供复核的效果回评")
        private Boolean available;
        @Schema(description = "排产情景主键")
        private Long scenarioId;
        @Schema(description = "排产情景编号")
        private String scenarioNo;
        @Schema(description = "排产情景状态")
        private String scenarioStatus;
        @Schema(description = "当前PLAN回评、MES执行事实和处置分类的SHA-256综合指纹")
        private String effectFingerprint;
        @Schema(description = "本次回评采用的计划/实际允许偏差分钟数")
        private Integer toleranceMinutes;
        @Schema(description = "当前回评结论摘要")
        private String summary;
        @Schema(description = "建议的下一步动作")
        private String nextAction;
        @Schema(description = "发布候选中计划排产的工序总数")
        private Integer plannedOperationCount;
        @Schema(description = "能够与MES生产工序精确匹配的工序数")
        private Integer matchedOperationCount;
        @Schema(description = "尚未与MES生产工序匹配的工序数")
        private Integer unmatchedOperationCount;
        @Schema(description = "实际开工时间超出容差的工序数")
        private Integer startDeviationOperationCount;
        @Schema(description = "实际完工时间超出容差的工序数")
        private Integer finishDeviationOperationCount;
        @Schema(description = "超过计划开工时刻和回评容差但仍未实际开工的工序数")
        private Integer pendingStartOverdueOperationCount;
        @Schema(description = "MES明确允许移动、可进入确定性局部重排评估的工序数")
        private Integer replanCandidateOperationCount;
        @Schema(description = "已经开工或完工、只能做偏差复盘而不能改写历史计划的工序数")
        private Integer executionLockedDeviationCount;
        @Schema(description = "需先修复PLAN/MES映射或来源证据后才能判断的工序数")
        private Integer dataRepairRequiredCount;
        @Schema(description = "按未匹配和偏差绝对值排序的重点工序，最多30条")
        private List<PlnSchedulingEffectReviewDTO.OperationEvidence> priorityDeviations;
        @Schema(description = "重点偏差工序是否因数量限制被截断")
        private Boolean deviationTruncated;
        @Schema(description = "当前观察中的MES来源或工序映射缺口，最多20条")
        private List<String> dataGaps;
        @Schema(description = "逐工序确定性处置路由；模型只能解释，不能覆盖该分类")
        private List<DeviationDisposition> deviationDispositions;
        @Schema(description = "PLAN效果回评及不可变观察历史；尚未采集时为空")
        private PlnSchedulingEffectReviewDTO.Detail detail;
        @Schema(description = "证据查询时刻")
        private LocalDateTime capturedTime;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "APS执行偏差的确定性处置分类")
    public static class DeviationDisposition {
        private Long issueId;
        private String issueNo;
        private String mesTaskCode;
        private String processCode;
        private String processName;
        private Integer operationSeq;
        private String mesStatus;
        @Schema(description = "DATA_REPAIR_REQUIRED、LOCAL_REPLAN_CANDIDATE、EXECUTION_BLOCKED、EXECUTION_LOCKED_REVIEW或OBSERVE")
        private String disposition;
        @Schema(description = "CRITICAL、WARNING或INFO")
        private String severity;
        private Boolean replanEligible;
        private Long pendingStartOverdueMinutes;
        private Long startDeviationMinutes;
        private Long finishDeviationMinutes;
        private String reason;
        private String recommendedWorkflow;
        private List<String> evidenceRefs;
    }

    @Data
    @Schema(description = "智能排产Agent可选行业目标模板查询")
    public static class ObjectiveTemplateQuery {
        @Schema(description = "工厂编码")
        private String factoryCode;
        @Schema(description = "行业标识，可选")
        private String industryCode;
        @Schema(description = "模板编码，可选")
        private String templateCode;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "智能排产Agent可用行业目标模板目录")
    public static class ObjectiveTemplateCatalog {
        private String factoryCode;
        private List<ObjectiveTemplateOption> templates;
        private LocalDateTime capturedTime;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "已批准行业目标模板版本及完整候选参数")
    public static class ObjectiveTemplateOption {
        @com.fasterxml.jackson.databind.annotation.JsonSerialize(
                using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class)
        private Long templateId;
        private String templateCode;
        private String templateName;
        private String industryCode;
        private Integer versionNo;
        private List<PlnSchedulingCandidateProfileDTO> candidateProfiles;
        @Schema(description = "模板头、版本和完整候选参数的SHA-256指纹")
        private String templateFingerprint;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "排产 Agent 正式发布回执")
    public static class PublishReceipt {
        @Schema(description = "情景主键")
        private Long scenarioId;
        @Schema(description = "情景编号")
        private String scenarioNo;
        @Schema(description = "正式发布的排程任务主键")
        private Long taskId;
        @Schema(description = "发布后情景状态")
        private String scenarioStatus;
        @Schema(description = "发布业务幂等号")
        private String publishRequestId;
        @Schema(description = "发布命令指纹")
        private String publishRequestFingerprint;
        @Schema(description = "受控执行审批编号")
        private String agentApprovalNo;
        @Schema(description = "发布人编码")
        private String publishedBy;
        @Schema(description = "发布时间")
        private LocalDateTime publishedTime;
    }
}
