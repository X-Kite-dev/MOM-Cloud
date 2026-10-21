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
 * 聚合报工部分纠正后的差异结案单。
 *
 * <p>结案只解释“已经纠正的报工数量最终是否重新报回”。实物报废、质量不合格和在制损失
 * 必须进入相应质量或损失流程，不能使用本单据核销。申请时冻结重报许可和实际重报事实，
 * 异岗批准时再次取证；事实变化则自动失效。</p>
 */
@Data
@TenantTable
@Accessors(chain = true)
@TableName("mes_report_correction_settlement")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "聚合报工差异结案单")
public class MesReportCorrectionSettlementEntity extends BaseEntity<MesReportCorrectionSettlementEntity> {

    @Schema(description = "结案单号")
    private String settlementNo;
    @Schema(description = "部分纠正申请ID")
    private Long reverseRequestId;
    @Schema(description = "部分纠正申请单号")
    private String reverseRequestNo;
    @Schema(description = "原报工ID")
    private Long reportId;
    @Schema(description = "生产工单号")
    private String taskCode;
    @Schema(description = "工序任务号")
    private String produceTaskCode;
    @Schema(description = "派工单号")
    private String arrangeCode;
    @Schema(description = "原纠正数量")
    private BigDecimal correctionQuantity;
    @Schema(description = "申请时已受控重报数量")
    private BigDecimal reReportedQuantity;
    @Schema(description = "申请时仍未重报数量")
    private BigDecimal unresolvedQuantity;
    @Schema(description = "结案类型：REPORTED_COMPLETE全部重报或COUNTING_ERROR_CONFIRMED确认原计数错误")
    private String resolutionType;
    @Schema(description = "外部证据引用，如盘点单、班次记录或附件编号")
    private String evidenceReference;
    @Schema(description = "结案事实和差异原因说明")
    private String evidenceDescription;
    @Schema(description = "申请时事实指纹")
    private String evidenceFingerprint;
    @Schema(description = "申请时事实快照JSON")
    private String evidenceSnapshot;
    @Schema(description = "SUBMITTED/APPROVED/REJECTED/WITHDRAWN/STALE")
    private String status;
    @Schema(description = "提交永久幂等请求号")
    private String submitRequestId;
    @Schema(description = "提交命令指纹")
    private String submitFingerprint;
    @Schema(description = "申请人ID")
    private Long applicantId;
    @Schema(description = "申请人工号")
    private String applicantCode;
    @Schema(description = "申请人姓名")
    private String applicantName;
    @Schema(description = "提交时间")
    private LocalDateTime submittedTime;
    @Schema(description = "复核永久幂等请求号")
    private String reviewRequestId;
    @Schema(description = "复核命令指纹")
    private String reviewFingerprint;
    @Schema(description = "复核结论")
    private Boolean approved;
    @Schema(description = "复核意见")
    private String reviewReason;
    @Schema(description = "复核人ID")
    private Long reviewerId;
    @Schema(description = "复核人工号")
    private String reviewerCode;
    @Schema(description = "复核人姓名")
    private String reviewerName;
    @Schema(description = "复核时间")
    private LocalDateTime reviewedTime;
    @Schema(description = "结案结果快照JSON")
    private String resultSnapshot;
    @Schema(description = "结案结果说明")
    private String resultMessage;
    @Schema(description = "实际工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
