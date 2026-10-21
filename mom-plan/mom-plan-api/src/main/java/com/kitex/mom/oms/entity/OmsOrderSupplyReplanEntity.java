package com.kitex.mom.oms.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * OMS订单变更供应级联重排主账。
 *
 * <p>业务单冻结订单变更版本、PLAN需求、MRP及下游供应事实，经异岗复核后分阶段撤回尚未执行的
 * 自制、采购、外协和物料预留，随后复用OMS正式评审完成需求改写与MRP重算。</p>
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("oms_order_supply_replan")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "OMS订单变更供应级联重排主账")
public class OmsOrderSupplyReplanEntity extends BaseEntity<OmsOrderSupplyReplanEntity> {
    @Schema(description = "级联重排业务单号")
    private String replanNo;
    @Schema(description = "OMS订单变更版本ID")
    private Long orderChangeVersionId;
    @Schema(description = "OMS订单变更评审ID")
    private Long reviewId;
    @Schema(description = "销售订单号")
    private String orderCode;
    @Schema(description = "提交幂等号")
    private String submitRequestId;
    @Schema(description = "提交载荷指纹")
    private String requestFingerprint;
    @Schema(description = "订单变更前事实快照")
    private String beforeSnapshot;
    @Schema(description = "订单变更目标快照")
    private String proposedSnapshot;
    @Schema(description = "供应级联综合证据指纹")
    private String evidenceFingerprint;
    @Schema(description = "申请原因")
    private String reason;
    @Schema(description = "SUBMITTED、REJECTED、APPROVED、EXECUTING、WAITING_PMS_REVIEW、FAILED、READY_FOR_FINAL_REVIEW、READY_FOR_REISSUE、SUCCEEDED、STALE、CANCELLED")
    private String status;
    @Schema(description = "申请人工号")
    private String applicantCode;
    @Schema(description = "申请人姓名")
    private String applicantName;
    @Schema(description = "申请时间")
    private LocalDateTime submittedTime;
    @Schema(description = "复核幂等号")
    private String reviewRequestId;
    @Schema(description = "复核载荷指纹")
    private String reviewFingerprint;
    @Schema(description = "是否批准")
    private Boolean approved;
    @Schema(description = "复核意见")
    private String reviewReason;
    @Schema(description = "复核人工号")
    private String reviewerCode;
    @Schema(description = "复核人姓名")
    private String reviewerName;
    @Schema(description = "复核时间")
    private LocalDateTime reviewedTime;
    @Schema(description = "稳定执行请求号")
    private String executionRequestId;
    @Schema(description = "重试次数")
    private Integer retryCount;
    @Schema(description = "供应撤回阶段状态：PENDING、WAITING_PMS_REVIEW、SUCCEEDED")
    private String supplyPhaseStatus;
    @Schema(description = "供应撤回阶段说明")
    private String supplyPhaseMessage;
    @Schema(description = "供应撤回完成时间")
    private LocalDateTime supplyHandledTime;
    @Schema(description = "WMS预留释放阶段状态")
    private String reservationPhaseStatus;
    @Schema(description = "WMS预留释放阶段说明")
    private String reservationPhaseMessage;
    @Schema(description = "WMS预留释放完成时间")
    private LocalDateTime reservationHandledTime;
    @Schema(description = "OMS最终评审和MRP重算阶段状态")
    private String finalReviewStatus;
    @Schema(description = "OMS最终评审和MRP重算阶段说明")
    private String finalReviewMessage;
    @Schema(description = "OMS最终评审完成时间")
    private LocalDateTime finalReviewHandledTime;
    @Schema(description = "新MRP重下达阶段状态")
    private String reissuePhaseStatus;
    @Schema(description = "新MRP重下达阶段说明")
    private String reissuePhaseMessage;
    @Schema(description = "新MRP重下达完成时间")
    private LocalDateTime reissueHandledTime;
    @Schema(description = "最近失败原因")
    private String lastError;
    @Schema(description = "最终结果快照")
    private String resultSnapshot;
    @Schema(description = "完成时间")
    private LocalDateTime completedTime;
    @TableField(fill = FieldFill.INSERT)
    @Schema(description = "工厂编码")
    private String factoryCode;
}
