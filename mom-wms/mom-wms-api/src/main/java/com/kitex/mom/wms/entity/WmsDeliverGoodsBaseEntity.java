package com.kitex.mom.wms.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.util.List;

/**
 * 发货单基础表
 *
 * @author kitex
 * @date 2025-01-13 09:31:20
 */
@Data
@TenantTable
@TableName("wms_deliver_goods_base")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "发货单基础表")
public class WmsDeliverGoodsBaseEntity extends BaseEntity<WmsDeliverGoodsBaseEntity> {


    /**
     * 发货单号
     */
    @Schema(description = "发货单号")
    private String deliverCode;

    /**
     * 出库仓编码
     */
    @Schema(description = "出库仓编码")
    private String warehouseCode;

    /**
     * 出库仓名称
     */
    @Schema(description = "出库仓名称")
    private String warehouseName;

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
     * 客户仓库
     */
    @Schema(description = "客户仓库")
    private String customerWarehouse;

    /**
     * 客户收货地址
     */
    @Schema(description = "客户收货地址")
    private String customerAddress;

    /**
     * 实际发货时间
     */
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd")
    @Schema(description = "实际发货时间")
    private LocalDate deliverTime;

    /**
     * 发货车牌
     */
    @Schema(description = "发货车牌")
    private String deliverCarLicense;

    /**
     * 送货人员
     */
    @Schema(description = "送货人员")
    private String deliverPerson;

    /**
     * 送货人员信息
     */
    @Schema(description = "送货人员信息")
    private String deliverPersonMsg;

    /**
     * 状态 0未开始1进行中2已完成3已关闭
     */
    @Schema(description = "状态 0未开始1进行中2已完成3已关闭")
    private String deliverState;

    /**
     * 补发标记 0否 1是
     */
    @Schema(description = "补发标记")
    private String reissueSign;


    /**
     * 是否下发 0否 1是
     */
    @Schema(description = "是否下发")
    private String issued;

    /**
     * 来源 add 新增  plan 计划
     */
    @Schema(description = "来源")
    private String source;

    /**
     * 客户实收异常处置生成的补发单来源；普通发货为空。
     */
    @Schema(description = "客户交付异常处置单ID")
    private Long exceptionResolutionCaseId;

    /**
     * 补发对应的原发货单ID。
     */
    @Schema(description = "原发货单ID")
    private Long originalDeliveryId;

    /**
     * 创建人名称
     */
    @Schema(description = "创建人名称")
    private String createByName;

    /**
     * 更新人名称
     */
    @Schema(description = "更新人名称")
    private String updateByName;


    /**
     * 发货计划单号
     */
    @TableField(exist = false)
    @Schema(description = "发货计划单号")
    private String deliverPlanCode;


    /**
     * 退货单ID
     */
    @TableField(exist = false)
    @Schema(description = "退货单ID")
    private String returnId;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

    /**
     * 发货单明细
     */
    @TableField(exist = false)
    @Schema(description = "发货单明细")
    private List<WmsDeliverGoodsDetailsEntity> deliverDetailsList;

    /**
     * 物料明细
     */
    @TableField(exist = false)
    @Schema(description = "物料明细")
    private List<WmsOutBillDetailEntity> deliverProductList;
}
