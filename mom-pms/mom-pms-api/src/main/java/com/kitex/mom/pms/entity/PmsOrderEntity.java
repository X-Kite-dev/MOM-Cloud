package com.kitex.mom.pms.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.github.yulichang.annotation.EntityMapping;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 采购订单表
 *
 * @author kitex
 * @date 2025-01-07 13:36:28
 */
@Data
@TenantTable
@TableName("pms_order")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "采购订单表")
public class PmsOrderEntity extends BaseEntity<PmsOrderEntity> {

    /**
     * 采购订单号
     */
    @Schema(description = "采购订单号")
    private String pmsOrderNo;

    /**
     * 供应商编码
     */
    @Schema(description = "供应商编码")
    private String supplierCode;

    /**
     * 供应商名称
     */
    @Schema(description = "供应商名称")
    private String supplierName;

    /**
     * 状态（0 未审核 1 审核中 2 审核通过 3 收货中 4 部分到货 5 完成收货 6 驳回 7 已取消 8 部分到货后取消）
     */
    @Schema(description = "状态（0 未审核 1 审核中 2 审核通过 3 收货中 4 部分到货 5 完成收货 6 驳回 7 已取消 8 部分到货后取消）")
    private String status;

    @Schema(description = "最近一次成功执行的订单变更申请ID")
    private Long lastChangeRequestId;

    @Schema(description = "已审批订单受控变更版本，从0开始递增")
    private Integer changeVersion;

    @Schema(description = "最近一次受控变更完成时间")
    private java.time.LocalDateTime lastChangedTime;

    @Schema(description = "最近一次受控变更原因")
    private String lastChangeReason;

    @Schema(description = "订单剩余量取消时间")
    private java.time.LocalDateTime cancelledTime;

    @Schema(description = "订单剩余量取消原因")
    private String cancelReason;

    /**
     * 类型 false 采购计划生成 true 采购订单新增
     */
    @Schema(description = "类型 false 采购计划生成 true 采购订单新增")
    private Boolean type;

    /**
     * 审批意见
     */
    @Schema(description = "审批意见")
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String checkReason;

    /**
     * 是否签订合同 true 是 false 否
     */
    @Schema(description = "是否签订合同 true 是 false 否")
    private Boolean contractStatus;

    @Schema(description = "订单来源：MANUAL、AGENT_SHORTAGE")
    private String sourceType;

    @Schema(description = "Agent采购模式：RELEASE_PLAN、SUPPLEMENT_PLAN")
    private String agentPurchaseMode;

    @Schema(description = "Agent采购执行永久幂等号")
    private String agentRequestId;

    @Schema(description = "Agent采购冻结命令摘要")
    private String agentCommandHash;

    @Schema(description = "Agent采购来源MRP记录")
    private Long agentMrpId;

    @Schema(description = "Agent采购冻结业务类型")
    private String agentBusinessType;

    @Schema(description = "Agent采购冻结业务单号")
    private String agentBusinessNo;

    @Schema(description = "Agent采购候选证据指纹")
    private String agentEvidenceFingerprint;

    @Schema(description = "Agent采购价格库记录")
    private Long agentPriceId;

    @Schema(description = "Agent采购供应商认证记录")
    private Long agentAuthorizationId;

    @Schema(description = "Agent采购申请原因")
    private String agentReason;

    /**
     * 工厂编码
     */
    @Schema(description = "工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

    @TableField(exist = false)
    @EntityMapping(thisField = "id", joinField = "pmsOrderId")
    private List<PmsOrderDetailEntity> pmsOrderDetailList;
}
