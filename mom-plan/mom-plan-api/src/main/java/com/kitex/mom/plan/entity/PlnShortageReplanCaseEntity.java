package com.kitex.mom.plan.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 缺料局部重排候选方案。
 *
 * <p>一条记录代表某个多候选情景中的一个可评估方案，保存生成方案时的 MRP、APS 和 MES 证据指纹，
 * 以及从申请、选案、发布到回退的完整审计状态。正式排程只有在方案发布后才会被修改。</p>
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("pln_shortage_replan_case")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "缺料局部重排候选方案")
public class PlnShortageReplanCaseEntity extends BaseEntity<PlnShortageReplanCaseEntity> {
    @Schema(description = "方案编号")
    private String caseNo;

    @Schema(description = "候选生成幂等号；多候选时包含候选编码后缀")
    private String requestId;

    @Schema(description = "候选生成请求快照（JSON）")
    private String requestSnapshot;

    @Schema(description = "缺料 MRP 记录主键")
    private Long mrpId;

    @Schema(description = "受影响生产计划主键")
    private Long issueId;

    @Schema(description = "受影响生产计划编号")
    private String issueNo;

    @Schema(description = "缺料物料编码")
    private String materialCode;

    @Schema(description = "缺料物料名称")
    private String materialName;

    @Schema(description = "发布前正式 APS 排程任务主键")
    private Long originalTaskId;

    @Schema(description = "候选仿真排程任务主键")
    private Long proposalTaskId;

    /**
     * 复用APS统一多候选情景头，候选任务与缺料差异方案均归属该情景。
     */
    @Schema(description = "APS 多候选情景主键")
    private Long scenarioId;

    @Schema(description = "有正式供应证据支撑的预计齐料时刻")
    private LocalDateTime expectedSupplyTime;

    @Schema(description = "直接受缺料影响的工序编码，多个值以逗号分隔")
    private String affectedProcessCodes;

    @Schema(description = "排程冻结区截止时刻")
    private LocalDateTime freezeUntil;

    @Schema(description = "策略允许的最大完工延期分钟数")
    private Integer maxDelayMinutes;

    @Schema(description = "直接受影响工序数量")
    private Integer directAffectedCount;

    @Schema(description = "候选方案实际移动工序数量")
    private Integer movedOperationCount;

    @Schema(description = "单道工序最大移动分钟数")
    private Long maximumShiftMinutes;

    @Schema(description = "原正式排程完工时刻")
    private LocalDateTime originalCompletionTime;

    @Schema(description = "候选排程完工时刻")
    private LocalDateTime proposedCompletionTime;

    @Schema(description = "交付风险", allowableValues = {"ON_TIME", "AT_RISK", "LATE"})
    private String deliveryRisk;

    @Schema(description = "方案生成时的 MES 执行指纹")
    private String mesExecutionFingerprint;

    @Schema(description = "MRP 及正式供应证据指纹")
    private String mrpEvidenceFingerprint;

    @Schema(description = "发布前正式排程指纹")
    private String originalScheduleFingerprint;

    @Schema(description = "发布前正式工序排程快照（JSON）")
    private String originalScheduleSnapshot;

    @Schema(description = "候选移动工序排程快照（JSON）")
    private String proposedScheduleSnapshot;

    @Schema(description = "方案证据总指纹")
    private String proposalFingerprint;

    @Schema(description = "方案状态", allowableValues = {"PROPOSED", "SELECTED", "SUPERSEDED", "PUBLISHED",
            "EXPIRED", "CANCELLED", "ROLLED_BACK"})
    private String status;

    @Schema(description = "发起重排的业务原因")
    private String reason;

    @Schema(description = "申请人编码")
    private String applicantCode;

    @Schema(description = "申请人名称")
    private String applicantName;

    @Schema(description = "方案生成时刻")
    private LocalDateTime proposedTime;

    @Schema(description = "方案失效时刻")
    private LocalDateTime expiresTime;

    @Schema(description = "发布业务幂等号")
    private String publishRequestId;

    @Schema(description = "发布人编码")
    private String publisherCode;

    @Schema(description = "发布人名称")
    private String publisherName;

    @Schema(description = "正式发布时间")
    private LocalDateTime publishedTime;

    @Schema(description = "发布完成后的 MES 执行指纹")
    private String mesPublishedFingerprint;

    @Schema(description = "取消业务幂等号")
    private String cancelRequestId;

    @Schema(description = "取消原因")
    private String cancelReason;

    @Schema(description = "取消人编码")
    private String cancelledBy;

    @Schema(description = "取消人名称")
    private String cancelledByName;

    @Schema(description = "取消时刻")
    private LocalDateTime cancelledTime;

    @Schema(description = "回退业务幂等号")
    private String rollbackRequestId;

    @Schema(description = "回退原因")
    private String rollbackReason;

    @Schema(description = "回退复核人编码")
    private String rolledBackBy;

    @Schema(description = "回退复核人名称")
    private String rolledBackByName;

    @Schema(description = "回退完成时刻")
    private LocalDateTime rolledBackTime;

    @Schema(description = "回退完成后的 MES 执行指纹")
    private String mesRollbackFingerprint;

    @TableField(fill = FieldFill.INSERT)
    @Schema(description = "工厂编码")
    private String factoryCode;

    @TableField(exist = false)
    @Schema(description = "所属情景编号，只读")
    private String scenarioNo;

    @TableField(exist = false)
    @Schema(description = "候选策略编码，只读")
    private String candidateCode;

    @TableField(exist = false)
    @Schema(description = "候选策略名称，只读")
    private String candidateName;

    @TableField(exist = false)
    @Schema(description = "候选综合评分，只读")
    private java.math.BigDecimal candidateScore;

    @TableField(exist = false)
    @Schema(description = "候选排名，只读")
    private Integer candidateRank;

    @TableField(exist = false)
    @Schema(description = "候选是否满足全部硬约束和延期策略，只读")
    private Boolean candidateFeasible;

    @TableField(exist = false)
    @Schema(description = "是否为当前情景选中的候选，只读")
    private Boolean selectedCandidate;

    @TableField(exist = false)
    @Schema(description = "候选评估指标快照（JSON），只读")
    private String candidateMetricsSnapshot;
}
