package com.kitex.mom.plan.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 一条产品需求的全量MPS投放行；原交期不被计划日期覆盖。
 */
@Data
@Accessors(chain = true)
@TenantTable
@EqualsAndHashCode(callSuper = true)
@TableName("pln_mps_line")
public class PlnMpsLineEntity extends BaseEntity<PlnMpsLineEntity> {
    private Long versionId;
    private Long demandId;
    private String demandNo;
    /**
     * 同一需求内从1开始且连续的跨期投放桶序号。
     */
    private Integer bucketNo;
    /**
     * 跨版本、MRP、供应和MES追溯使用的不可变时间桶键。
     */
    private String bucketKey;
    private String productCode;
    private String warehouseCode;
    private BigDecimal planQuantity;
    private LocalDate sourceDueDate;
    private LocalDate plannedDate;
    private Integer mrpPriority;
    private Integer projectedLateDays;
    /**
     * RCCP承诺资源：默认工艺路线产线、经启用路线核验的显式替代产线，或物料默认工作中心。
     */
    private String capacityResourceCode;
    private String capacityResourceName;
    /**
     * ROUTE_RATE、QUALIFIED_ROUTE_RATE或STANDARD_HOURS。
     */
    private String capacityBasis;
    /**
     * 本需求占用的粗能力分钟。
     */
    private BigDecimal capacityDemandMinutes;
    /**
     * 本次RCCP计算得到的纯加工分钟，不单独落库，冻结在能力证据快照内。
     */
    @TableField(exist = false)
    private BigDecimal capacityProcessingMinutes;
    /**
     * 本次RCCP计算得到的产线产品族换型分钟，不单独落库，冻结在能力证据快照内。
     */
    @TableField(exist = false)
    private BigDecimal capacityChangeoverMinutes;
    @TableField(exist = false)
    private String capacityFromFamilyCode;
    @TableField(exist = false)
    private String capacityToFamilyCode;
    @TableField(exist = false)
    private String capacityChangeoverRuleNo;
    @TableField(exist = false)
    private Integer capacityChangeoverRuleVersion;
    /**
     * 本次预演的可读核验说明，不落库；完整依据冻结在证据快照。
     */
    @TableField(exist = false)
    private String capacityEvaluationMessage;
    /**
     * 不含本候选时，该资源/日期已有的活动MPS承诺分钟。
     */
    private BigDecimal capacityCommittedMinutes;
    private BigDecimal capacityAvailableMinutes;
    private BigDecimal capacityLoadRate;
    /**
     * NOT_REQUIRED、PASS、OVERLOAD或UNVERIFIED。
     */
    private String capacityStatus;
    private Boolean capacityVerified;
    /**
     * 冻结加工、换型、日历和活动承诺等完整粗能力证据。
     */
    private String capacityEvidenceSnapshot;
    private String capacityEvidenceFingerprint;
    /**
     * MDM正式生产批量策略ID；为空表示提交时该产品尚未配置正式策略。
     */
    private Long lotPolicyId;
    private String lotPolicyNo;
    private Integer lotPolicyVersion;
    private BigDecimal minimumBatchQuantity;
    private BigDecimal economicBatchQuantity;
    private BigDecimal maximumBatchQuantity;
    private BigDecimal batchMultiple;
    private LocalDateTime lotPolicyValidStartTime;
    private LocalDateTime lotPolicyValidEndTime;
    /**
     * 提交时完整生产批量策略目录的证据指纹，包括未配置产品集合。
     */
    private String lotPolicyEvidenceFingerprint;
    private String sourceFingerprint;
    private String sourceSnapshot;
    /**
     * DIRECT 或 WITHDRAW_REPLAN。
     */
    private String revisionMode;
    /**
     * 旧 MRP、供应、MES、WMS 和 APS 的组合证据指纹。
     */
    private String downstreamEvidenceFingerprint;
    /**
     * 可供失败续跑复用的完整下游冻结证据。
     */
    private String downstreamEvidenceSnapshot;
    /**
     * NOT_APPLICABLE、PENDING、FAILED 或 SUCCEEDED。
     */
    private String executionStatus;
    /**
     * 本行跨模块处理回执。
     */
    private String executionMessage;
    /**
     * 本行跨模块处理完成时间。
     */
    private LocalDateTime executionTime;
    private Boolean pending;
    private Boolean active;
    private String factoryCode;

    /**
     * RCCP读侧责任种类，非新增实物计划行，不落库。
     */
    @TableField(exist = false)
    private String capacityResponsibilityType;
    /**
     * 被剩余责任精确替代的原MPS行；预留及普通活动行也保留原身份。
     */
    @TableField(exist = false)
    private Long capacityOriginalLineId;
    /**
     * 当前剩余承诺案件，只读虚拟投影不修改原MPS。
     */
    @TableField(exist = false)
    private Long capacityCommitmentId;
    /**
     * 逾期装载后仍保存原承诺日，不掩盖原延期。
     */
    @TableField(exist = false)
    private LocalDate capacityOriginalDate;
}
