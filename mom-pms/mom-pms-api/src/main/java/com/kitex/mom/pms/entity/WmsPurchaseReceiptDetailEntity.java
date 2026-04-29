package com.kitex.mom.pms.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 采购收料单明细
 *
 * @author kitex
 * @date 2025-01-22 11:34:35
 */
@Data
@TenantTable
@TableName("wms_purchase_receipt_detail")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "采购收料单明细")
public class WmsPurchaseReceiptDetailEntity extends BaseEntity<WmsPurchaseReceiptDetailEntity> {

    /**
     * 采购收料单id
     */
    @Schema(description = "采购收料单id")
    private Long billId;

    /**
     * 采购模块-采购订单明细id
     */
    @Schema(description = "采购模块-采购订单明细id")
    private Long orderDetailId;

    /**
     * 物料编码
     */
    @Schema(description = "物料编码")
    private String matCode;
    /**
     * 物料名称
     */
    @Schema(description = "物料名称")
    private String matName;
    /**
     * 物料单位
     */
    @Schema(description = "物料单位")
    private String matUnit;
    /**
     * 物料规格
     */
    @Schema(description = "物料规格")
    private String matSpec;
    /**
     * 应收数量
     */
    @Schema(description = "应收数量")
    private Double shouldNum;
    /**
     * 实收数量
     */
    @Schema(description = "实收数量")
    private Double actualNum;
    /**
     * 可入库数
     */
    @Schema(description = "可入库数")
    private Double inableNum;
    /**
     * 实际入库数
     */
    @Schema(description = "实际入库数")
    private Double inNum;
    /**
     * 退货数量
     */
    @Schema(description = "退货数量")
    private Double rejectNum;
//	/**
//	 * 状态;待处理、处理中、已完成
//	 * 单据创建为待处理，开始收料为处理中，收料结束提交质检直到质检完成都是处理中，生成通知单为已完成
//	 */
//	@Schema(description = "状态;待处理、处理中、已完成")
//	private String state;

    /**
     * 物料批次
     */
    @Schema(description = "物料批次")
    private String matBatch;

    /**
     * 是否生成仓库单 (false=否, true=是)
     */
    @Schema(description = "是否生成仓库单 (false=否, true=是)")
    private Boolean wmsStatus;

    /**
     * 生产日期
     */
    @Schema(description = "生产日期")
    private LocalDate matProduceDate;
    /**
     * 单价
     */
    @Schema(description = "单价")
    private BigDecimal price;

    /**
     * 是否免检 (false=否, true=是)
     */
    @Schema(description = "是否免检 (false=否, true=是)")
    private Boolean isQuality;
}