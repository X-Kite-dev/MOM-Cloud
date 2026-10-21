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

import java.time.LocalDateTime;

/**
 * 批次召回治理主单。
 *
 * <p>该对象冻结申请时的跨域事实和摘要；正式隔离通过WMS业务命令执行，不能直接改库存。</p>
 */
@Data
@Accessors(chain = true)
@TenantTable
@EqualsAndHashCode(callSuper = true)
@TableName("qms_batch_recall")
@Schema(description = "批次召回候选治理单")
public class QmsBatchRecallEntity extends BaseEntity<QmsBatchRecallEntity> {
    private String recallNo;
    private String requestId;
    private String materialCode;
    private String batchNo;
    private String severity;
    private String reason;
    private String status;
    /**
     * 申请时MES/WMS/QMS完整证据JSON，用于审计而不是前端模糊统计。
     */
    private String evidenceSnapshot;
    private String evidenceHash;
    /**
     * 排除WMS可变库存状态后的下游影响摘要，用于关闭前识别影响范围漂移。
     */
    private String downstreamScopeHash;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime observedAt;
    private Long mesMatchedReportCount;
    private Long wmsPositiveStockRowCount;
    private Long qmsOpenNcrCount;
    private Boolean customerNotificationRequired;
    private String proposedBy;
    private String proposedByName;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime proposedTime;
    private String reviewedBy;
    private String reviewedByName;
    private String reviewRemark;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime reviewedTime;
    private String approvedEvidenceHash;
    private String executedBy;
    private String executedByName;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime executedTime;
    private String verifiedBy;
    private String verifiedByName;
    private String verificationRemark;
    private String verificationConclusion;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime verifiedTime;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime closedTime;
    private String lastError;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
