package com.kitex.mom.plan.dto;

import com.kitex.mom.plan.entity.PlnShortageReplanCaseEntity;
import com.kitex.mom.plan.entity.PlnShortageReplanLineEntity;
import com.kitex.mom.plan.entity.PlnSchedulingScenarioEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 缺料局部重排接口模型。
 *
 * <p>“指纹”字段用于乐观并发控制和审批证据固化，不是普通展示字段。调用方必须回传查询或上一步操作
 * 返回的原值，不允许自行计算或修改。</p>
 */
public final class PlanShortageLocalReplanDTO {

    private PlanShortageLocalReplanDTO() {
    }

    @Data
    @Schema(description = "缺料局部重排候选生成命令")
    public static class ProposeCommand {
        @Schema(description = "业务幂等号，同一编号只能用于完全相同的请求", requiredMode = Schema.RequiredMode.REQUIRED,
                maxLength = 48)
        private String requestId;

        @Schema(description = "工厂编码；未传时取当前工厂上下文", requiredMode = Schema.RequiredMode.REQUIRED)
        private String factoryCode;

        @Schema(description = "发生缺料的 MRP 记录主键", requiredMode = Schema.RequiredMode.REQUIRED)
        private Long mrpId;

        @Schema(description = "生产计划主键；未传时根据 MRP 需求号匹配正式锁定计划")
        private Long issueId;

        @Schema(description = "有正式供应证据支撑的预计齐料时刻", requiredMode = Schema.RequiredMode.REQUIRED)
        private LocalDateTime expectedSupplyTime;

        @Schema(description = "直接受影响工序编码；为空时由 MDM 工序投料关系自动识别")
        private List<String> affectedProcessCodes;
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        @Schema(description = "原冻结路线节点选择；旧主工序选择仅可唯一对应原工序")
        private List<String> affectedSourceRouteNodeCodes;

        @Schema(description = "排程冻结区分钟数，范围 30 至 10080", example = "120")
        private Integer freezeMinutes;

        @Schema(description = "策略允许的最大完工延期分钟数，范围 1 至 527040", example = "10080")
        private Integer maxDelayMinutes;

        @Schema(description = "发起局部重排的业务原因", requiredMode = Schema.RequiredMode.REQUIRED)
        private String reason;
    }

    @Data
    @Schema(description = "缺料局部重排分页筛选条件")
    public static class Query {
        @Schema(description = "方案编号，支持模糊查询")
        private String caseNo;

        @Schema(description = "生产计划编号，支持模糊查询")
        private String issueNo;

        @Schema(description = "缺料物料编码，支持模糊查询")
        private String materialCode;

        @Schema(description = "方案状态", allowableValues = {"PROPOSED", "SELECTED", "SUPERSEDED", "PUBLISHED",
                "EXPIRED", "CANCELLED", "ROLLED_BACK"})
        private String status;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    @Schema(description = "缺料局部重排候选方案详情")
    public static class Proposal extends PlnShortageReplanCaseEntity {
        @Schema(description = "工序级排程差异明细")
        private List<PlnShortageReplanLineEntity> lines;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "一次缺料重排情景及其全部候选")
    public static class Scenario {
        @Schema(description = "APS 多候选情景头")
        private PlnSchedulingScenarioEntity scenario;

        @Schema(description = "按候选排名升序排列的方案")
        private List<Proposal> candidates;
    }

    @Data
    @Schema(description = "缺料重排候选选择命令")
    public static class SelectCommand {
        @Schema(description = "选案业务幂等号", requiredMode = Schema.RequiredMode.REQUIRED)
        private String requestId;

        @Schema(description = "被选择的候选方案主键", requiredMode = Schema.RequiredMode.REQUIRED)
        private Long candidateCaseId;

        @Schema(description = "候选生成时返回的情景来源指纹", requiredMode = Schema.RequiredMode.REQUIRED)
        private String expectedSourceFingerprint;

        @Schema(description = "选择该候选的业务原因", requiredMode = Schema.RequiredMode.REQUIRED)
        private String reason;
    }

    @Data
    @Schema(description = "缺料局部重排正式发布命令")
    public static class PublishCommand {
        @Schema(description = "待发布方案主键", requiredMode = Schema.RequiredMode.REQUIRED)
        private Long caseId;

        @Schema(description = "发布业务幂等号", requiredMode = Schema.RequiredMode.REQUIRED)
        private String requestId;

        @Schema(description = "方案所属工厂编码", requiredMode = Schema.RequiredMode.REQUIRED)
        private String factoryCode;

        @Schema(description = "方案对应的 MRP 记录主键", requiredMode = Schema.RequiredMode.REQUIRED)
        private Long mrpId;

        @Schema(description = "方案对应的生产计划编号", requiredMode = Schema.RequiredMode.REQUIRED)
        private String issueNo;

        @Schema(description = "待发布方案指纹", requiredMode = Schema.RequiredMode.REQUIRED)
        private String expectedProposalFingerprint;

        @Schema(description = "审批冻结时的 MES 执行指纹", requiredMode = Schema.RequiredMode.REQUIRED)
        private String expectedMesExecutionFingerprint;
    }

    @Data
    @Schema(description = "缺料局部重排取消命令")
    public static class CancelCommand {
        @Schema(description = "待取消方案主键", requiredMode = Schema.RequiredMode.REQUIRED)
        private Long caseId;

        @Schema(description = "取消业务幂等号", requiredMode = Schema.RequiredMode.REQUIRED)
        private String requestId;

        @Schema(description = "取消原因", requiredMode = Schema.RequiredMode.REQUIRED)
        private String reason;
    }

    @Data
    @Schema(description = "缺料局部重排受控回退命令")
    public static class RollbackCommand {
        @Schema(description = "已发布方案主键", requiredMode = Schema.RequiredMode.REQUIRED)
        private Long caseId;

        @Schema(description = "回退业务幂等号", requiredMode = Schema.RequiredMode.REQUIRED)
        private String requestId;

        @Schema(description = "原发布方案指纹", requiredMode = Schema.RequiredMode.REQUIRED)
        private String expectedProposalFingerprint;

        @Schema(description = "发布成功后的 MES 执行指纹", requiredMode = Schema.RequiredMode.REQUIRED)
        private String expectedMesExecutionFingerprint;

        @Schema(description = "回退原因", requiredMode = Schema.RequiredMode.REQUIRED)
        private String reason;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "缺料局部重排发布或回退回执")
    public static class PublishReceipt {
        @Schema(description = "方案主键")
        private Long caseId;

        @Schema(description = "方案编号")
        private String caseNo;

        @Schema(description = "生产计划编号")
        private String issueNo;

        @Schema(description = "操作后方案状态", allowableValues = {"PUBLISHED", "ROLLED_BACK"})
        private String status;

        @Schema(description = "本次方案移动的工序数量")
        private Integer movedOperationCount;

        @Schema(description = "操作后的计划完工时刻")
        private LocalDateTime newCompletionTime;

        @Schema(description = "MES 操作完成后的执行指纹，供后续回退或并发校验使用")
        private String mesAfterExecutionFingerprint;
    }
}
