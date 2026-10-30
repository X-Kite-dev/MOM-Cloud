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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 已排产/已下达生产计划受控变更主账。
 *
 * <p>保存申请证据、异岗复核结果、跨模块阶段回执及失败续跑状态。原计划作为历史版本关闭，
 * 批准后的目标需求重新执行MRP并返回待排产，禁止在已下达计划上原地覆盖数量或交期。</p>
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("pln_issue_change")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "生产计划受控变更主账")
public class PlnIssueChangeEntity extends BaseEntity<PlnIssueChangeEntity> {
    /**
     * 业务变更单号。
     */
    private String changeNo;
    /**
     * 提交申请的永久幂等请求号。
     */
    private String submitRequestId;
    /**
     * 提交命令载荷的 SHA-256 指纹。
     */
    private String requestFingerprint;
    /**
     * 被变更生产计划主键。
     */
    private Long issueId;
    /**
     * 被变更生产计划编号。
     */
    private String issueNo;
    /**
     * 生产计划来源产品需求主键。
     */
    private Long demandId;
    /**
     * 生产计划来源产品需求编号。
     */
    private String demandNo;
    /**
     * 计划产品编码。
     */
    private String productCode;
    /**
     * 申请前计划数量。
     */
    private BigDecimal beforeQuantity;
    /**
     * 申请前需求交期。
     */
    private LocalDate beforeDemandDate;
    /**
     * 申请目标数量。
     */
    private BigDecimal targetQuantity;
    /**
     * 申请目标交期。
     */
    private LocalDate targetDemandDate;
    /**
     * PLAN、供应、MES 与目标载荷的组合证据指纹。
     */
    private String evidenceFingerprint;
    /**
     * 产品需求、MRP、APS 等本地事实指纹。
     */
    private String localEvidenceFingerprint;
    /**
     * MRP 下层自制、采购、外协供应证据指纹。
     */
    private String supplyEvidenceFingerprint;
    /**
     * MES 未开工撤回资格证据指纹。
     */
    private String mesEvidenceFingerprint;
    /**
     * 下层供应冻结证据，用于失败后的原单续跑。
     */
    private String supplyEvidenceSnapshot;
    /**
     * 申请时完整业务证据快照。
     */
    private String evidenceSnapshot;
    /**
     * 变更业务原因。
     */
    private String reason;
    /**
     * SUBMITTED、WITHDRAWN、REJECTED、APPROVED、EXECUTING、PARTIAL、FAILED、SUCCEEDED、STALE。
     */
    private String status;
    /**
     * 申请人账号。
     */
    private String applicantCode;
    /**
     * 申请人姓名。
     */
    private String applicantName;
    /**
     * 申请提交时间。
     */
    private LocalDateTime submittedTime;
    /**
     * 复核命令永久幂等请求号。
     */
    private String reviewRequestId;
    /**
     * 复核是否批准。
     */
    private Boolean approved;
    /**
     * 复核意见或依据。
     */
    private String reviewReason;
    /**
     * 复核人账号。
     */
    private String reviewerCode;
    /**
     * 复核人姓名。
     */
    private String reviewerName;
    /**
     * 复核完成时间。
     */
    private LocalDateTime reviewedTime;
    /**
     * 跨模块执行永久请求号。
     */
    private String executionRequestId;
    /**
     * 人工续跑次数。
     */
    private Integer retryCount;
    /**
     * 下层供应撤回阶段状态。
     */
    private String supplyPhaseStatus;
    /**
     * 下层供应撤回阶段回执。
     */
    private String supplyPhaseMessage;
    /**
     * 下层供应阶段处理时间。
     */
    private LocalDateTime supplyHandledTime;
    /**
     * MES 撤回阶段状态。
     */
    private String mesPhaseStatus;
    /**
     * MES 撤回阶段回执。
     */
    private String mesPhaseMessage;
    /**
     * MES 阶段处理时间。
     */
    private LocalDateTime mesHandledTime;
    /**
     * WMS 预留及 Pegging 释放阶段状态。
     */
    private String wmsPhaseStatus;
    /**
     * WMS 阶段回执。
     */
    private String wmsPhaseMessage;
    /**
     * WMS 阶段处理时间。
     */
    private LocalDateTime wmsHandledTime;
    /**
     * APS 资源占用解除阶段状态。
     */
    private String apsPhaseStatus;
    /**
     * APS 阶段回执。
     */
    private String apsPhaseMessage;
    /**
     * APS 阶段处理时间。
     */
    private LocalDateTime apsHandledTime;
    /**
     * 原计划关闭及目标需求 MRP 重算阶段状态。
     */
    private String planPhaseStatus;
    /**
     * PLAN/MRP 阶段回执。
     */
    private String planPhaseMessage;
    /**
     * PLAN/MRP 阶段处理时间。
     */
    private LocalDateTime planHandledTime;
    /**
     * 最近一次跨模块执行错误。
     */
    private String lastError;
    /**
     * 成功后的旧计划、新需求、新 MRP 及下一动作快照。
     */
    private String resultSnapshot;
    /**
     * 全流程完成时间。
     */
    private LocalDateTime completedTime;
    /**
     * 业务工厂编码，创建时从工厂上下文写入。
     */
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
