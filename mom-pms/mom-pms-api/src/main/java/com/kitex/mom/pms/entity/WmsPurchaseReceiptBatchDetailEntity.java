package com.kitex.mom.pms.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * 采购收料单收料明细
 *
 * @author kitex
 * @date 2025-01-22 18:02:43
 */
@Data
@TenantTable
@TableName("wms_purchase_receipt_batch_detail")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "采购收料单收料明细")
public class WmsPurchaseReceiptBatchDetailEntity extends BaseEntity<WmsPurchaseReceiptBatchDetailEntity> {

    /**
     * 采购收料单id
     */
    @Schema(description = "采购收料单id")
    private Long billId;

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
     * 物料批次
     */
    @Schema(description = "物料批次")
    private String matBatch;

    /**
     * 生产日期
     */
    @Schema(description = "生产日期")
    private LocalDate matProduceDate;

    /**
     * 收料数量
     */
    @Schema(description = "收料数量")
    private Double matNum;

    /**
     * 判定数量
     */
    @Schema(description = "判定数量")
    private Double qualityNum;

    /**
     * 实际入库数
     */
    @Schema(description = "实际入库数")
    private Double inNum;

    /**
     * 合格品是否已生成入库通知单
     */
    @Schema(description = "合格品是否已生成入库通知单")
    private String qualifiedGen;

    /**
     * 不合格品是否已生成入库通知单
     */
    @Schema(description = "不合格品是否已生成入库通知单")
    private String unQualifiedGen;

    /**
     * 状态;待处理、处理中、质检完成、已完成
     */
    @Schema(description = "状态;待处理、处理中、质检完成、已完成")
    private String state;

    /**
     * 质检任务id 生成质检任务后返回过来
     */
    @Schema(description = "质检任务id")
    private Long qualityId;

    /**
     * 质检结果
     */
    @Schema(description = "质检结果")
    private String qualityResult;
}