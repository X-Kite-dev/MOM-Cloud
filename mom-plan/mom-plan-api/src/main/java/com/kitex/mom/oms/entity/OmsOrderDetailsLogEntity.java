package com.kitex.mom.oms.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 订单交付信息-操作日志表
 *
 * @author kitex
 * @date 2025-04-18 17:35:48
 */
@Data
@TenantTable
@TableName("oms_order_details_log")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "订单交付信息-操作日志表")
public class OmsOrderDetailsLogEntity extends BaseEntity<OmsOrderDetailsLogEntity> {
    /**
     * 所属订单
     */
    @Schema(description = "所属订单")
    private String orderCode;

    /**
     * 订单交付信息表id
     */
    @Schema(description = "订单交付信息表id")
    private Long orderDetailId;

    /**
     * 操作类型
     */
    @Schema(description = "操作类型")
    private String type;

    /**
     * 操作内容
     */
    @Schema(description = "操作内容")
    private String content;

    /**
     * 状态
     */
    @Schema(description = "状态")
    private String status;

    /**
     * 数量
     */
    @Schema(description = "数量")
    private BigDecimal num;
}