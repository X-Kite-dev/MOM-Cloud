package com.kitex.mom.plan.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.github.yulichang.annotation.EntityMapping;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 计划下达表
 *
 * @author kitex
 * @date 2025-02-06 11:49:55
 */
@Data
@TenantTable
@TableName("pln_issue")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "计划下达表")
public class PlnIssueEntity extends BaseEntity<PlnIssueEntity> {
    /** 仅本次引擎内传递，结果持久化时单独保存，不写来源计划或远端业务实体。 */
    @com.baomidou.mybatisplus.annotation.TableField(exist=false)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String sequenceEvidenceJson;

    /**
     * 生产计划下达号
     */
    @Schema(description = "生产计划下达号")
    private String issueNo;

    /**
     * 客户编码
     */
    @Schema(description = "客户编码")
    private String customerCode;

    /**
     * 客户名称
     */
    @Schema(description = "客户名称")
    private String customerName;

    /**
     * 产品编码
     */
    @Schema(description = "产品编码")
    private String productCode;

    /**
     * 产品名称
     */
    @Schema(description = "产品名称")
    private String productName;

    /**
     * 规格型号
     */
    @Schema(description = "规格型号")
    private String specificationModel;

    /**
     * 物料属性
     */
    @Schema(description = "产品属性")
    private String materialAttribute;

    /**
     * 数量
     */
    @Schema(description = "数量")
    private BigDecimal num;

    /**
     * 下达时间
     */
    @Schema(description = "下达时间")
    private LocalDateTime issueTime;

    /**
     * 需求时间
     */
    @Schema(description = "需求时间")
    private LocalDate demandTime;

    /**
     * 计划类型
     */
    @Schema(description = "计划类型")
    private String planType;

    /**
     * 完成数量
     */
    @Schema(description = "完成数量")
    private BigDecimal finishNum;

    @Schema(description = "MES直接不合格上报累计数量")
    private BigDecimal unqualifiedNum;

    @Schema(description = "QMS已隔离累计数量")
    private BigDecimal qualityIsolatedNum;

    @Schema(description = "QMS放行或让步接收累计数量")
    private BigDecimal qualityReleasedNum;

    @Schema(description = "QMS质量判退累计数量")
    private BigDecimal qualityRejectedNum;

    @Schema(description = "已审批报废净累计数量")
    private BigDecimal scrapNum;

    @Schema(description = "质量处置发起返工累计数量")
    private BigDecimal reworkNum;

    @Schema(description = "返工完工累计数量")
    private BigDecimal reworkCompletedNum;

    @Schema(description = "返工复检放行累计数量")
    private BigDecimal reworkQualifiedNum;

    @Schema(description = "返工复检拒绝累计数量")
    private BigDecimal reworkRejectedNum;

    /**
     * 完成时间
     */
    @Schema(description = "完成时间")
    private LocalDate finishTime;

    /**
     * 备注
     */
    @Schema(description = "备注")
    private String remark;

    /**
     * 来源类型
     */
    @Schema(description = "来源类型")
    private String type;

    /**
     * 状态, 0:未下发, 1:已下发, 2: 生产中, 3: 已完成, 4:已关闭
     */
    @Schema(description = "状态, 0:未下发, 1:已下发, 2: 生产中, 3: 已完成, 4:已关闭")
    private String status;

    /**
     * 排产分配日期
     */
    @Schema(description = "排产分配日期")
    private LocalDate scheduledDate;

    @Schema(description = "排产结束日期")
    private LocalDate scheduledEndDate;

    /**
     * 是否锁定
     */
    @Schema(description = "是否锁定")
    private Boolean lockStatus;

    /**
     * 锁定任务ID
     */
    @Schema(description = "锁定任务ID")
    private Long lockTaskId;

    /**
     * 锁定时间
     */
    @Schema(description = "锁定时间")
    private LocalDateTime lockTime;

    /**
     * 工厂编码
     */
    @Schema(description = "工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

    @Schema(description = "规则命中信息(JSON)")
    @TableField(exist = false)
    private String matchedRules;

    @Schema(description = "评分拆解(JSON)")
    @TableField(exist = false)
    private String scoreBreakdown;

    @Schema(description = "排产物料约束状态")
    @TableField(exist = false)
    private String materialConstraintStatus;

    @Schema(description = "物料预计齐套后的最早可开工日期")
    @TableField(exist = false)
    private LocalDate materialAvailableDate;

    @Schema(description = "物料逐层汇合后的分钟级最早可开工时刻")
    @TableField(exist = false)
    private LocalDateTime materialAvailableTime;

    @Schema(description = "物料约束是否硬阻断")
    @TableField(exist = false)
    private Boolean materialHardBlocked;

    @Schema(description = "物料约束业务摘要")
    @TableField(exist = false)
    private String materialConstraintSummary;

    @Schema(description = "物料约束明细JSON快照")
    @TableField(exist = false)
    private String materialConstraintSnapshot;

    @Schema(description = "逐工序物料汇合约束JSON快照")
    @TableField(exist = false)
    private String operationMaterialConstraintSnapshot;

    @Schema(description = "本次MRP BOM路径证据综合指纹")
    @TableField(exist = false)
    private String materialLineageFingerprint;

    @Schema(description = "物料约束最近复核时间")
    @TableField(exist = false)
    private LocalDateTime materialConstraintCheckedTime;

    @Schema(description = "工序级有限产能排程状态")
    @TableField(exist = false)
    private String operationScheduleStatus;

    @Schema(description = "工序级排程是否硬阻断")
    @TableField(exist = false)
    private Boolean operationHardBlocked;

    @Schema(description = "工序级排程摘要")
    @TableField(exist = false)
    private String operationScheduleSummary;

    @Schema(description = "排产工序数量")
    @TableField(exist = false)
    private Integer operationCount;

    @Schema(description = "首道工序计划开始时间")
    @TableField(exist = false)
    private LocalDateTime operationStartTime;

    @Schema(description = "末道工序计划结束时间")
    @TableField(exist = false)
    private LocalDateTime operationEndTime;

    /**
     * 下达反馈列表
     */
    @TableField(exist = false)
    @EntityMapping(thisField = "issueNo", joinField = "plnIssueNo")
    private List<PlnIssueFeedbackEntity> feedbackEntities;
}
