package com.kitex.mom.plan.dto;

import com.kitex.mom.plan.entity.*;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 紧急插单局部重排接口模型。
 *
 * <p>指纹字段是跨步骤审批的一致性依据，调用方必须原样回传上一步结果，不允许自行拼装。</p>
 */
public final class PlanInsertionLocalReplanDTO {

    private PlanInsertionLocalReplanDTO() {
    }

    @Data
    @Schema(description = "插单重排策略查询条件")
    public static class PolicyQuery {
        @Schema(description = "策略编码")
        private String policyCode;

        @Schema(description = "策略状态", allowableValues = {"DRAFT", "ACTIVE", "RETIRED"})
        private String status;
    }

    @Data
    @Schema(description = "插单重排策略草稿命令")
    public static class PolicyDraftCommand {
        @Schema(description = "策略主键；为空时新建草稿")
        private Long id;
        @Schema(description = "策略编码")
        private String policyCode;
        @Schema(description = "策略名称")
        private String policyName;
        @Schema(description = "适用工厂编码")
        private String factoryCode;
        @Schema(description = "排程冻结区分钟数")
        private Integer freezeMinutes;
        @Schema(description = "受影响计划搜索时域（天）")
        private Integer impactHorizonDays;
        @Schema(description = "单次最多受影响生产计划数")
        private Integer maxAffectedIssues;
        @Schema(description = "单次最多移动工序数")
        private Integer maxMovedOperations;
        @Schema(description = "全部受影响计划累计最大延期分钟数")
        private Integer maxTotalDelayMinutes;
        @Schema(description = "单个生产计划最大延期分钟数")
        private Integer maxSingleIssueDelayMinutes;
        @Schema(description = "插单自身允许的最大延期分钟数")
        private Integer maxInsertionLateMinutes;
        @Schema(description = "方案允许新增的延期计划数量")
        private Integer maxNewLateIssues;
        @Schema(description = "策略匹配优先级，数值越小优先级越高")
        private Integer priority;
        @Schema(description = "策略生效时刻")
        private LocalDateTime effectiveFrom;
        @Schema(description = "策略失效时刻")
        private LocalDateTime effectiveTo;
    }

    @Data
    @Schema(description = "插单重排策略状态变更命令")
    public static class PolicyActionCommand {
        @Schema(description = "策略主键")
        private Long policyId;
        @Schema(description = "业务幂等号")
        private String requestId;
        @Schema(description = "状态变更原因")
        private String reason;
    }

    @Data
    @Schema(description = "插单重排方案查询条件")
    public static class CaseQuery {
        @Schema(description = "方案编号")
        private String caseNo;
        @Schema(description = "插单生产计划编号")
        private String insertionIssueNo;
        @Schema(description = "方案状态")
        private String status;
    }

    @Data
    @Schema(description = "插单影响评估命令")
    public static class AssessCommand {
        @Schema(description = "插单生产计划主键")
        private Long insertionIssueId;
        @Schema(description = "评估业务幂等号")
        private String requestId;
        @Schema(description = "紧急等级")
        private String urgencyLevel;
        @Schema(description = "业务要求的最早开工时刻")
        private LocalDateTime requestedStartTime;
        @Schema(description = "插单业务原因")
        private String businessReason;
    }

    @Data
    @Schema(description = "插单重排正式发布命令")
    public static class PublishCommand {
        @Schema(description = "待发布方案主键")
        private Long caseId;
        @Schema(description = "发布业务幂等号")
        private String requestId;
        @Schema(description = "候选方案指纹")
        private String expectedProposalFingerprint;
        @Schema(description = "物料齐套证据指纹")
        private String expectedMaterialEvidenceFingerprint;
        @Schema(description = "审批冻结时的 MES 执行指纹")
        private String expectedMesExecutionFingerprint;
    }

    @Data
    @Schema(description = "插单重排取消命令")
    public static class CancelCommand {
        @Schema(description = "待取消方案主键")
        private Long caseId;
        @Schema(description = "取消业务幂等号")
        private String requestId;
        @Schema(description = "取消原因")
        private String reason;
    }

    @Data
    @Schema(description = "插单重排候选选择命令")
    public static class SelectCommand {
        @Schema(description = "选案业务幂等号")
        private String requestId;
        @Schema(description = "被选择的候选方案主键")
        private Long candidateCaseId;
        @Schema(description = "候选生成时的情景来源指纹")
        private String expectedSourceFingerprint;
        @Schema(description = "人工选择原因")
        private String reason;
    }

    @Data
    @Schema(description = "插单重排受控回退命令")
    public static class RollbackCommand {
        @Schema(description = "已发布方案主键")
        private Long caseId;
        @Schema(description = "回退业务幂等号")
        private String requestId;
        @Schema(description = "原发布方案指纹")
        private String expectedProposalFingerprint;
        @Schema(description = "发布成功后的 MES 执行指纹")
        private String expectedMesExecutionFingerprint;
        @Schema(description = "回退原因")
        private String reason;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    @Schema(description = "插单重排方案详情")
    public static class CaseDetail extends PlnInsertionReplanCaseEntity {
        @Schema(description = "插单及被挤占生产计划明细")
        private List<PlnInsertionReplanIssueEntity> issues;
        @Schema(description = "工序级排程差异")
        private List<PlnInsertionReplanLineEntity> lines;
        @Schema(description = "方案操作审计记录")
        private List<PlnInsertionReplanOperationEntity> operations;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "插单重排情景及其候选")
    public static class Scenario {
        @Schema(description = "APS 多候选情景头")
        private PlnSchedulingScenarioEntity scenario;
        @Schema(description = "按候选排名排列的插单方案")
        private List<CaseDetail> candidates;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "插单重排发布或回退回执")
    public static class PublishReceipt {
        @Schema(description = "方案主键")
        private Long caseId;
        @Schema(description = "方案编号")
        private String caseNo;
        @Schema(description = "插单生产计划编号")
        private String insertionIssueNo;
        @Schema(description = "操作后方案状态")
        private String status;
        @Schema(description = "正式发布的 APS 任务主键")
        private Long publishedTaskId;
        @Schema(description = "受影响生产计划数量")
        private Integer affectedIssueCount;
        @Schema(description = "移动工序数量")
        private Integer movedOperationCount;
        @Schema(description = "方案导致的新增延期计划数量")
        private Integer newLateIssueCount;
        @Schema(description = "插单计划的新完工时刻")
        private LocalDateTime insertionCompletionTime;
        @Schema(description = "MES 操作完成后的执行指纹")
        private String mesAfterExecutionFingerprint;
    }
}
