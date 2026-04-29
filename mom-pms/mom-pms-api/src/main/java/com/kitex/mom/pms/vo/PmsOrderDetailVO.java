package com.kitex.mom.pms.vo;

import com.kitex.common.core.entity.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 采购订单明细VO
 *
 * @author kitex
 * @date 2025-01-07 13:36:28
 */
@Data
@Schema(description = "采购订单明细VO")
public class PmsOrderDetailVO extends BaseEntity<PmsOrderDetailVO> {

    /**
     * 采购订单号
     */
    @Schema(description = "采购订单号")
    private String pmsOrderNo;

    /**
     * 采购计划号
     */
    @Schema(description = "采购计划号")
    private String pmsPlanNo;

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
     * 状态（0 未审核 1 审核中 2 审核通过 3 收货中 4 部分到货 5 完成收货 6 驳回）
     */
    @Schema(description = "状态（0 未审核 1 审核中 2 审核通过 3 收货中 4 部分到货 5 完成收货 6 驳回）")
    private String status;

    /**
     * 审批意见
     */
    @Schema(description = "审批意见")
    private String checkReason;

    /**
     * 创建时间
     */
    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    /**
     * 工厂编码
     */
    @Schema(description = "工厂编码")
    private String factoryCode;
}