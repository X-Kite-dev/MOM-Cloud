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

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * OMS订单变更供应级联逐MRP执行行及永久回执。
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("oms_order_supply_replan_line")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "OMS订单变更供应级联逐MRP执行行")
public class OmsOrderSupplyReplanLineEntity extends BaseEntity<OmsOrderSupplyReplanLineEntity> {
    @Schema(description = "供应级联主账ID")
    private Long replanId;
    @Schema(description = "主账内稳定执行顺序")
    private Integer sequenceNo;
    @Schema(description = "OMS原交付行ID")
    private Long orderDetailId;
    @Schema(description = "PLAN产品需求ID")
    private Long demandId;
    @Schema(description = "PLAN产品需求号")
    private String demandNo;
    @Schema(description = "MRP明细ID")
    private Long mrpId;
    @Schema(description = "MRP物料编码")
    private String materialCode;
    @Schema(description = "SELF、PURCHASE、OUTSOURCE等精确供应类型")
    private String supplyType;
    @Schema(description = "供应来源业务对象ID")
    private Long supplySourceId;
    @Schema(description = "供应来源业务单号")
    private String supplySourceNo;
    @Schema(description = "冻结时的供应数量")
    private BigDecimal supplyQuantity;
    @Schema(description = "MRP及供应来源冻结证据指纹")
    private String evidenceFingerprint;
    @Schema(description = "PMS或MES下游执行事实指纹")
    private String downstreamEvidenceFingerprint;
    @Schema(description = "冻结的MRP、供应来源及下游事实快照")
    private String evidenceSnapshot;
    @Schema(description = "供应撤回状态：PENDING、WAITING_PMS_REVIEW、SUCCEEDED、NOT_APPLICABLE")
    private String supplyStatus;
    @Schema(description = "权威供应撤回接口返回状态")
    private String supplyReceiptStatus;
    @Schema(description = "供应撤回回执说明")
    private String supplyMessage;
    @Schema(description = "供应撤回完成时间")
    private LocalDateTime supplyHandledTime;
    @Schema(description = "需先受控撤回的PMS采购订单ID")
    private Long pmsOrderId;
    @Schema(description = "需先受控撤回的PMS采购订单号")
    private String pmsOrderNo;
    @Schema(description = "需先受控撤回的PMS采购订单行ID")
    private Long pmsOrderDetailId;
    @Schema(description = "PMS订单行撤回申请ID")
    private Long pmsChangeRequestId;
    @Schema(description = "PMS订单行撤回申请单号")
    private String pmsChangeRequestNo;
    @Schema(description = "PMS订单行撤回永久请求号")
    private String pmsChangeSubmitRequestId;
    @Schema(description = "申请时冻结的PMS订单行证据指纹")
    private String pmsEvidenceFingerprint;
    @Schema(description = "PMS撤回状态：NOT_REQUIRED、SUBMITTED、SUCCEEDED、REJECTED、STALE")
    private String pmsChangeStatus;
    @Schema(description = "PMS撤回回执说明")
    private String pmsChangeMessage;
    @Schema(description = "PMS撤回终态接收时间")
    private LocalDateTime pmsChangeHandledTime;
    @Schema(description = "PMS撤回成功后重新冻结的MRP供应证据指纹")
    private String executionEvidenceFingerprint;
    @Schema(description = "PMS撤回成功后重新冻结的下游供应证据指纹")
    private String executionDownstreamEvidenceFingerprint;
    @Schema(description = "主料、替代料预留及Pegging释放状态：PENDING、SUCCEEDED")
    private String reservationStatus;
    @Schema(description = "预留释放回执说明")
    private String reservationMessage;
    @Schema(description = "预留释放完成时间")
    private LocalDateTime reservationHandledTime;
    @TableField(fill = FieldFill.INSERT)
    @Schema(description = "工厂编码")
    private String factoryCode;
}
