package com.kitex.mom.pms.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
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
 * 采购收料单
 *
 * @author kitex
 * @date 2025-01-22 11:34:35
 */
@Data
@TenantTable
@TableName("wms_purchase_receipt")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "采购收料单")
public class WmsPurchaseReceiptEntity extends BaseEntity<WmsPurchaseReceiptEntity> {

    /**
     * 采购收料单号
     */
    @Schema(description = "采购收料单号")
    private String billNo;

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
     * 仓库编码
     */
    @Schema(description = "仓库编码")
    private String warehouseCode;

    /**
     * 仓库名称
     */
    @Schema(description = "仓库名称")
    private String warehouseName;

    /**
     * 状态
     * 0待处理、1处理中、2质检提交、3质检完成、4已完成
     * （明细有一个处理中即为处理中，全部质检完成才为质检完成，全部已完成才为已完成）
     */
    @Schema(description = "状态")
    private String state;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

    /**
     * 备注
     */
    @Schema(description = "备注")
    private String remark;

    /**
     * 入库通知单生成类别 1合格品 2不合格品
     */
    @TableField(exist = false)
    @Schema(description = "入库通知单生成类别")
    private String genType;

    /**
     * 是否全部免检
     */
    @TableField(exist = false)
    @Schema(description = "是否全部免检")
    private Boolean qmsStatus;

    /**
     * 收料单明细
     */
    @TableField(exist = false)
    @Schema(description = "收料单明细")
    @EntityMapping(thisField = "id", joinField = "billId")
    private List<WmsPurchaseReceiptDetailEntity> details;

    /**
     * 扫码收料明细
     */
    @TableField(exist = false)
    @Schema(description = "扫码收料明细")
    @EntityMapping(thisField = "id", joinField = "billId")
    private List<WmsPurchaseReceiptBatchDetailEntity> batchDetails;
}