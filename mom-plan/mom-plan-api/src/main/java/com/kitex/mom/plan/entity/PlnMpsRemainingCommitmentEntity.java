package com.kitex.mom.plan.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 已开工来源的不可变剩余责任版本，发布不修改原MPS或实物账。 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("pln_mps_remaining_commitment")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "已开工来源的不可变剩余责任版本，发布不修改原MPS或实物账")
public class PlnMpsRemainingCommitmentEntity extends BaseEntity<PlnMpsRemainingCommitmentEntity> {
    /** 剩余责任版本业务号。 */
    @Schema(description = "剩余责任版本业务号")
    private String commitmentNo;

    /** 精确原MPS桶ID。 */
    @Schema(description = "精确原MPS桶ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long mpsLineId;

    /** 原MPS版本ID。 */
    @Schema(description = "原MPS版本ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long mpsVersionId;

    /** 原独立根生产计划ID。 */
    @Schema(description = "原独立根生产计划ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long rootIssueId;

    /** 原独立根生产计划号。 */
    @Schema(description = "原独立根生产计划号")
    private String rootIssueNo;

    /** 同原桶连续案件版本，取消/驳回也保留版本。 */
    @Schema(description = "同原桶连续案件版本，取消/驳回也保留版本")
    private Integer commitmentVersion;

    /** 编制时的前生效版本，首版为空；发布须再次精确匹配。 */
    @Schema(description = "编制时的前生效版本，首版为空；发布须再次精确匹配")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long previousCommitmentId;

    /** 已独立确认的原兑现观察ID。 */
    @Schema(description = "已独立确认的原兑现观察ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long observationId;

    /** 原确认观察的内部日期截止容差，兑现与审批保持同一口径。 */
    @Schema(description = "原确认观察的内部日期截止容差，兑现与审批保持同一口径")
    private Integer toleranceMinutes;

    /** 编制永久请求号。 */
    @Schema(description = "编制永久请求号")
    private String requestId;

    /** 实际组织/实名及规范化原载荷指纹。 */
    @Schema(description = "实际组织/实名及规范化原载荷指纹")
    private String requestFingerprint;

    /** 原产品编码。 */
    @Schema(description = "原产品编码")
    private String productCode;

    /** 原权威单位，禁止无依据换算。 */
    @Schema(description = "原权威单位，禁止无依据换算")
    private String productUnit;

    /** 原可信粗能力资源，非移派授权。 */
    @Schema(description = "原可信粗能力资源，非移派授权")
    private String capacityResourceCode;

    /** 原量Q，原MPS/root/MES数量不改变。 */
    @Schema(description = "原量Q，原MPS/root/MES数量不改变")
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal originalQuantity;

    /** 已核清来源报工B，不是入库或质量净放行量。 */
    @Schema(description = "已核清来源报工B，不是入库或质量净放行量")
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal baselineQuantity;

    /** 该版本原始剩余责任R=Q-B，当前责任另据事件计算。 */
    @Schema(description = "该版本原始剩余责任R=Q-B，当前责任另据事件计算")
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal remainingQuantity;

    /** 原MPS计划日，保留原延期。 */
    @Schema(description = "原MPS计划日，保留原延期")
    private LocalDate originalPlannedDate;

    /** 原客户交期，不能以新分期覆盖。 */
    @Schema(description = "原客户交期，不能以新分期覆盖")
    private LocalDate customerDueDate;

    /** 完整原基线正文指纹。 */
    @Schema(description = "完整原基线正文指纹")
    private String baselineFingerprint;

    /** 完整来源/逐报工基线JSON，最多8MiB，不截断。 */
    @Schema(description = "完整来源/逐报工基线JSON，最多8MiB，不截断")
    private String baselineSnapshot;

    /** 编制冻结的工厂MPS策略ID。 */
    @Schema(description = "编制冻结的工厂MPS策略ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long policyId;

    /** 工厂MPS策略版本。 */
    @Schema(description = "工厂MPS策略版本")
    private Integer policyVersion;

    /** 冻结策略证据指纹。 */
    @Schema(description = "冻结策略证据指纹")
    private String policyFingerprint;

    /** 冻结滚动/冻结区/能力模式策略正文。 */
    @Schema(description = "冻结滚动/冻结区/能力模式策略正文")
    private String policySnapshot;

    /** 编制时正式责任及能力核查指纹。 */
    @Schema(description = "编制时正式责任及能力核查指纹")
    private String capacityFingerprint;

    /** 完整编制能力依据，不以旧PASS直接发布。 */
    @Schema(description = "完整编制能力依据，不以旧PASS直接发布")
    private String capacitySnapshot;

    /** PENDING/PUBLISHED/REJECTED/STALE/CANCELLED，一次性终态。 */
    @Schema(description = "PENDING/PUBLISHED/REJECTED/STALE/CANCELLED，一次性终态")
    private String status;

    /** 实际实名编制账号，不能由客户端指定。 */
    @Schema(description = "实际实名编制账号，不能由客户端指定")
    private String applicantCode;

    /** 正式编制落账时刻。 */
    @Schema(description = "正式编制落账时刻")
    private LocalDateTime submittedTime;

    /** 完整业务编制原因。 */
    @Schema(description = "完整业务编制原因")
    private String reason;

    /** 独立复核永久请求号。 */
    @Schema(description = "独立复核永久请求号")
    private String reviewRequestId;

    /** 复核规范载荷及实际实名指纹。 */
    @Schema(description = "复核规范载荷及实际实名指纹")
    private String reviewRequestFingerprint;

    /** 实际独立复核账号，与编制人分离。 */
    @Schema(description = "实际独立复核账号，与编制人分离")
    private String reviewedBy;

    /** 一次性复核时刻。 */
    @Schema(description = "一次性复核时刻")
    private LocalDateTime reviewedTime;

    /** 复核/失效的事实说明。 */
    @Schema(description = "复核/失效的事实说明")
    private String reviewReason;

    /** 来源/守恒/能力/客户交期四项固定核验JSON。 */
    @Schema(description = "来源/守恒/能力/客户交期四项固定核验JSON")
    private String reviewChecksSnapshot;

    /** 复核时重新计算的正式能力指纹。 */
    @Schema(description = "复核时重新计算的正式能力指纹")
    private String reviewCapacityFingerprint;

    /** 复核时的完整能力依据，保留编制旧依据。 */
    @Schema(description = "复核时的完整能力依据，保留编制旧依据")
    private String reviewCapacitySnapshot;

    /** 待审编制取消原请求号。 */
    @Schema(description = "待审编制取消原请求号")
    private String cancelRequestId;

    /** 取消原载荷及实名指纹。 */
    @Schema(description = "取消原载荷及实名指纹")
    private String cancelRequestFingerprint;

    /** 实际编制人取消账号。 */
    @Schema(description = "实际编制人取消账号")
    private String cancelledBy;

    /** 取消一次性落账时刻。 */
    @Schema(description = "取消一次性落账时刻")
    private LocalDateTime cancelledTime;

    /** 取消原因，不删除原版本。 */
    @Schema(description = "取消原因，不删除原版本")
    private String cancelReason;

    /** 实际授权工厂编码，非客户端自选组织。 */
    @Schema(description = "实际授权工厂编码，非客户端自选组织")
    private String factoryCode;
}
