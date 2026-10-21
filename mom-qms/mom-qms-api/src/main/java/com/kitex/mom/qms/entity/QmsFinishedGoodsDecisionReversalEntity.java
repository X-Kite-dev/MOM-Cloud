package com.kitex.mom.qms.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 成品入库所依赖质量判定的受控逆向授权单。
 *
 * <p>该单据只授权下游撤销对既有质量判定的使用，不删除或篡改原质检任务和检验结果。
 * 授权与WMS通知、精确QMS任务集合、质量证据指纹和永久消费请求绑定。</p>
 */
@Data
@Accessors(chain = true)
@TenantTable
@EqualsAndHashCode(callSuper = true)
@TableName("qms_finished_goods_decision_reversal")
@Schema(description = "成品质量判定逆向授权单")
public class QmsFinishedGoodsDecisionReversalEntity
        extends BaseEntity<QmsFinishedGoodsDecisionReversalEntity> {

    @Schema(description = "逆向授权单号")
    private String reversalNo;

    @Schema(description = "申请永久幂等请求号")
    private String requestId;

    @Schema(description = "WMS成品入库通知ID")
    private Long wmsNoticeId;

    @Schema(description = "精确QMS任务ID JSON数组")
    private String sourceQualityTaskIds;

    @Schema(description = "MES/WMS冻结的质量证据指纹")
    private String sourceQualityEvidenceFingerprint;

    @Schema(description = "申请时QMS任务事实快照")
    private String qmsEvidenceSnapshot;

    @Schema(description = "申请时QMS任务事实指纹")
    private String qmsEvidenceFingerprint;

    @Schema(description = "SUBMITTED/APPROVED/REJECTED/WITHDRAWN/STALE/CONSUMED/EXECUTED")
    private String status;

    @Schema(description = "逆向原因")
    private String reason;

    private String applicantCode;
    private String applicantName;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime appliedTime;

    private String reviewerCode;
    private String reviewerName;
    private String reviewRemark;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime reviewedTime;

    @Schema(description = "批准时再次核验的QMS证据指纹")
    private String approvedQmsEvidenceFingerprint;

    @Schema(description = "不可预测的授权码；WMS内部消费时与授权ID共同校验")
    private String authorizationCode;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime authorizationExpiresAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "未消费授权被系统批量关闭的实际时间；非到期失效时为空")
    private LocalDateTime authorizationExpiredTime;

    @Schema(description = "消费授权的WMS永久请求号")
    private String consumedByRequestId;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime consumedTime;

    @Schema(description = "WMS最终实物红冲永久事件号")
    private String wmsExecutionEventId;

    @Schema(description = "WMS最终实物红冲事件指纹")
    private String wmsExecutionFingerprint;

    @Schema(description = "WMS红冲申请ID")
    private Long wmsCorrectionId;

    @Schema(description = "WMS最终红冲数量")
    private BigDecimal wmsReverseQuantity;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "WMS实物执行时间")
    private LocalDateTime wmsExecutedTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "QMS接收WMS永久结果时间")
    private LocalDateTime wmsExecutionReceivedTime;

    @Schema(description = "工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
