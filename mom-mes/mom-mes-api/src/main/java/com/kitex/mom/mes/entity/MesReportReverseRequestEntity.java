package com.kitex.mom.mes.entity;

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
import java.time.LocalDateTime;

/**
 * 生产报工冲销申请。
 *
 * <p>申请时冻结原报工、产出库存、实际投料、包装和流转事实；
 * 批准时由非申请人重新取证并执行反向业务事务。原报工和审批证据永久保留。</p>
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("mes_report_reverse_request")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "MES报工受控冲销申请")
public class MesReportReverseRequestEntity extends BaseEntity<MesReportReverseRequestEntity> {

    @Schema(description = "冲销申请单号")
    private String requestNo;
    @Schema(description = "提交永久幂等请求号")
    private String submitRequestId;
    @Schema(description = "提交命令指纹")
    private String submitFingerprint;
    @Schema(description = "原报工主键")
    private Long reportId;
    @Schema(description = "原报工永久请求号")
    private String reportRequestId;
    @Schema(description = "生产工单号")
    private String taskCode;
    @Schema(description = "工序任务号")
    private String produceTaskCode;
    @Schema(description = "派工单号")
    private String arrangeCode;
    @Schema(description = "报工数量")
    private BigDecimal reportNum;
    @Schema(description = "申请冻结时原报工的当前有效数量")
    private BigDecimal effectiveQuantityBefore;
    @Schema(description = "本次批准冲减数量；完整冲销等于申请前有效数量")
    private BigDecimal correctionQuantity;
    @Schema(description = "批准后剩余有效数量")
    private BigDecimal effectiveQuantityAfter;
    @Schema(description = "报工模式")
    private String reportMode;
    @Schema(description = "纠正范围：SINGLE完整单笔、IDENTIFIER_CHAIN同标识下游链或PARTIAL_QUANTITY聚合数量部分纠正")
    private String correctionMode;
    @Schema(description = "冻结并执行的报工数量")
    private Integer chainReportCount;
    @Schema(description = "SUBMITTED/APPROVED/REJECTED/WITHDRAWN/STALE")
    private String status;
    @Schema(description = "申请时完整事实指纹")
    private String evidenceFingerprint;
    @Schema(description = "申请时完整事实快照JSON")
    private String evidenceSnapshot;
    @Schema(description = "冲销原因")
    private String reason;
    @Schema(description = "申请人主键")
    private Long applicantId;
    @Schema(description = "申请人工号")
    private String applicantCode;
    @Schema(description = "申请人姓名")
    private String applicantName;
    @Schema(description = "提交时间")
    private LocalDateTime submittedTime;
    @Schema(description = "审批永久幂等请求号")
    private String reviewRequestId;
    @Schema(description = "审批命令指纹")
    private String reviewFingerprint;
    @Schema(description = "审批结论")
    private Boolean approved;
    @Schema(description = "审批依据")
    private String reviewReason;
    @Schema(description = "审批人主键")
    private Long reviewerId;
    @Schema(description = "审批人工号")
    private String reviewerCode;
    @Schema(description = "审批人姓名")
    private String reviewerName;
    @Schema(description = "审批时间")
    private LocalDateTime reviewedTime;
    @Schema(description = "执行或终止时间")
    private LocalDateTime handledTime;
    @Schema(description = "冲销执行结果快照JSON")
    private String resultSnapshot;
    @Schema(description = "处理结果说明")
    private String resultMessage;
    @Schema(description = "是否在批准后生成受控重报许可")
    private Boolean createReReportPermit;
    @Schema(description = "受控重报许可号")
    private String reReportPermitNo;
    @Schema(description = "受控重报允许数量")
    private BigDecimal reReportAllowedQuantity;
    @Schema(description = "受控重报已使用数量")
    private BigDecimal reReportUsedQuantity;
    @Schema(description = "受控重报许可状态 ACTIVE/USED/CANCELLED/EXPIRED")
    private String reReportPermitStatus;
    @Schema(description = "受控重报许可失效时间")
    private LocalDateTime reReportExpireTime;
    @TableField(fill = FieldFill.INSERT)
    @Schema(description = "实际工厂编码")
    private String factoryCode;
}
