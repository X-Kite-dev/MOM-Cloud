package com.kitex.mom.plan.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
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
    /**
     * 仅本次引擎内传递，结果持久化时单独保存，不写来源计划或远端业务实体。
     */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String sequenceEvidenceJson;

    /**
     * 生产计划下达号
     */
    @Schema(description = "生产计划下达号")
    private String issueNo;

    @Schema(description = "来源产品需求号；与唯一生产计划号分离")
    private String sourceDemandNo;

    @Schema(description = "来源MRP记录ID")
    private Long sourceMrpId;

    @Schema(description = "独立计划来源键；MPS跨期拆量时保存桶键")
    private String planningSourceKey;

    @Schema(description = "来源MPS需求桶行ID")
    private Long mpsLineId;

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

    @Schema(description = "最近一次普通计划下达MES的永久请求号")
    private String mesIssueRequestId;

    @Schema(description = "最近一次普通计划下达MES的载荷SHA-256指纹")
    private String mesIssueFingerprint;

    @Schema(description = "MES接收回执中的生产工单ID")
    private Long mesTaskId;

    @Schema(description = "MES接收回执中的生产工单编码")
    private String mesTaskCode;

    @Schema(description = "MES首次接收生产计划时间")
    private LocalDateTime mesIssueTime;

    /**
     * 生产计划精确绑定的已发布工程版本ID。
     */
    @Schema(description = "精确工程版本ID")
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long engineeringReleaseId;

    /**
     * 与工程版本ID共同组成生产消费版本对的冻结原文SHA256。
     */
    @Schema(description = "工程版本冻结原文SHA256")
    private String engineeringContentHash;

    /**
     * 路线内工程版本号，仅用于人员识别，不替代ID与指纹。
     */
    @Schema(description = "工程版本号")
    private Integer engineeringVersionNo;

    /**
     * 工程版本固定的路线ID。
     */
    @Schema(description = "工程版本路线ID")
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long engineeringRouteId;

    /**
     * 工程版本固定的路线编码。
     */
    @Schema(description = "工程版本路线编码")
    private String engineeringRouteCode;

    /**
     * 工程版本固定的BOM ID。
     */
    @Schema(description = "工程版本BOM ID")
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long engineeringBomId;

    /**
     * 工程版本固定的BOM业务版本。
     */
    @Schema(description = "工程版本BOM版本")
    private String engineeringBomVersion;

    /**
     * 最近一次工程版本绑定实名用户。
     */
    @Schema(description = "工程版本绑定人")
    private String engineeringBoundBy;

    /**
     * 最近一次工程版本绑定时间。
     */
    @Schema(description = "工程版本绑定时间")
    private LocalDateTime engineeringBoundTime;

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
