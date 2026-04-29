package com.kitex.mom.wms.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.activerecord.Model;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 发货记录表
 *
 * @author kitex
 * @date 2025-01-16 09:07:22
 */
@Data
@TableName("wms_deliver_goods_record")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "发货记录表")
public class WmsDeliverGoodsRecordEntity extends Model<WmsDeliverGoodsRecordEntity> {


    /**
     * id
     */
    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "id")
    private Long id;

    /**
     * 发货单号
     */
    @Schema(description = "发货单号")
    private String deliverCode;

    /**
     * 发货计划号
     */
    @Schema(description = "发货计划号")
    private String deliverPlanCode;

    /**
     * 订单号
     */
    @Schema(description = "订单号")
    private String orderCode;

    /**
     * 发货时间
     */
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd")
    @Schema(description = "发货时间")
    private LocalDate deliverTime;

    /**
     * 发货数量
     */
    @Schema(description = "发货数量")
    private BigDecimal deliveredNum;

    /**
     * 产品编码
     */
    @Schema(description = "产品编码")
    private String productCode;

    /**
     * 产品名称
     */
    @Schema(description = "产品名称")
    private String productName;

    /**
     * 客户编码
     */
    @Schema(description = "客户编码")
    private String customerCode;

    /**
     * 客户名称
     */
    @Schema(description = "客户名称")
    private String customerName;

    /**
     * 出库仓库编码
     */
    @Schema(description = "出库仓库编码")
    private String warehouseCode;

    /**
     * 出库仓库名称
     */
    @Schema(description = "出库仓库名称")
    private String warehouseName;

    /**
     * 补发标记 0否 1是
     */
    @Schema(description = "补发标记")
    private String reissueSign;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

}