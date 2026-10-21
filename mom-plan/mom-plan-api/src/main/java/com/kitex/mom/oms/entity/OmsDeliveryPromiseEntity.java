package com.kitex.mom.oms.entity;

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
 * 客户订单交期答复单。
 *
 * <p>答复单只冻结候选和岗位确认，不直接修改订单。候选与现行订单不一致时，必须关联
 * {@code oms_order_change_version} 并等待原有受控订单变更通过后，才能对客户正式答复。</p>
 */
@Data
@Accessors(chain = true)
@TenantTable
@EqualsAndHashCode(callSuper = true)
@TableName("oms_delivery_promise")
@Schema(description = "客户订单交期答复单")
public class OmsDeliveryPromiseEntity extends BaseEntity<OmsDeliveryPromiseEntity> {
    @Schema(description = "交期答复单号")
    private String promiseNo;
    @Schema(description = "提交永久幂等请求号")
    private String requestId;
    @Schema(description = "提交命令指纹")
    private String requestFingerprint;
    @Schema(description = "订单跟踪主记录ID")
    private Long orderTrackId;
    @Schema(description = "销售订单号")
    private String orderCode;
    @Schema(description = "客户编码")
    private String customerCode;
    @Schema(description = "客户名称")
    private String customerName;
    @Schema(description = "来源：MANUAL、EXTERNAL_SUGGESTION、RISK_WORKBENCH")
    private String sourceType;
    @Schema(description = "客户询期或内部协同凭证号")
    private String customerRequestRef;
    @Schema(description = "本轮交期答复原因和业务背景")
    private String reason;
    @Schema(description = "流程状态")
    private String status;
    @Schema(description = "提交时OMS/PLAN完整事实JSON")
    private String factSnapshot;
    @Schema(description = "提交时事实SHA-256")
    private String factHash;
    @Schema(description = "候选交付行标准化JSON")
    private String candidateSnapshot;
    @Schema(description = "候选交付行SHA-256")
    private String candidateHash;
    @Schema(description = "冻结事实的观察时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime observedAt;
    @Schema(description = "客户要求的答复期限")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime replyDueTime;
    @Schema(description = "提交人工号")
    private String submittedBy;
    @Schema(description = "提交人姓名")
    private String submittedByName;
    @Schema(description = "提交时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime submittedTime;
    @Schema(description = "销售岗位是否批准")
    private Boolean salesApproved;
    @Schema(description = "销售确认人工号")
    private String salesConfirmedBy;
    @Schema(description = "销售确认人姓名")
    private String salesConfirmedByName;
    @Schema(description = "销售岗位意见")
    private String salesRemark;
    @Schema(description = "销售确认时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime salesConfirmedTime;
    @Schema(description = "PMC岗位是否批准")
    private Boolean pmcApproved;
    @Schema(description = "PMC确认人工号")
    private String pmcConfirmedBy;
    @Schema(description = "PMC确认人姓名")
    private String pmcConfirmedByName;
    @Schema(description = "PMC岗位意见")
    private String pmcRemark;
    @Schema(description = "PMC确认时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime pmcConfirmedTime;
    @Schema(description = "由双岗确认触发的OMS订单变更请求号")
    private String changeRequestId;
    @Schema(description = "关联OMS订单变更版本ID")
    private Long changeVersionId;
    @Schema(description = "关联订单变更结果")
    private String orderChangeStatus;
    @Schema(description = "业务审批完成、允许对客答复的时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime readyTime;
    @Schema(description = "实际对客答复渠道")
    private String replyChannel;
    @Schema(description = "邮件、门户回执、录音或其他答复凭证号")
    private String replyReference;
    @Schema(description = "实际对客答复内容")
    private String replyContent;
    @Schema(description = "实际答复人工号")
    private String repliedBy;
    @Schema(description = "实际答复人姓名")
    private String repliedByName;
    @Schema(description = "实际对客答复时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime repliedTime;
    @Schema(description = "取消原因")
    private String cancelReason;
    @Schema(description = "所属工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
