package com.kitex.mom.oms.entity;

import com.alibaba.excel.annotation.ExcelIgnore;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import com.kitex.mom.oms.dto.HistoryDeliverOrderDto;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 订单输入
 *
 * @author kitex
 * @date 2025-01-07 14:20:47
 */
@Data
@TenantTable
@TableName("oms_order_base")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "订单输入")
public class OmsOrderBaseEntity extends BaseEntity<OmsOrderBaseEntity> {

    /**
     * 订单号
     */
    @Schema(description = "订单号")
    private String orderCode;

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
     * 规格型号
     */
    @Schema(description = "规格型号")
    private String productSpec;

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
     * 客户零件号
     */
    @Schema(description = "客户零件号")
    private String customerPartCode;

    /**
     * 订单类型
     */
    @Schema(description = "订单类型")
    private String orderType;

    /**
     * 订单状态 0 新建 1 待评审 2评审完成
     */
    @Schema(description = "订单状态 0 新建 1 待评审 2评审完成")
    private String orderState;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

    /**
     * 创建人名称
     */
    @ExcelIgnore
    @Schema(description = "创建人名称")
    private String createByName;

    /**
     * 更新人名称
     */
    @ExcelIgnore
    @Schema(description = "更新人名称")
    private String updateByName;

    /**
     * 交付信息详情列表
     */
    @ExcelIgnore
    @Schema(description = "交付信息详情列表")
    @TableField(exist = false)
    private List<OmsOrderDetailsEntity> orderDetailsList;

    /**
     * 历史未交订单
     */
    @ExcelIgnore
    @Schema(description = "历史未交订单")
    @TableField(exist = false)
    private List<HistoryDeliverOrderDto> historyDeliverOrderDtoList;


}