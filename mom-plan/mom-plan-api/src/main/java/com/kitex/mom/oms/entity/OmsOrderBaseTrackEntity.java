package com.kitex.mom.oms.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import com.kitex.mom.oms.dto.*;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 订单跟踪表
 *
 * @author kitex
 * @date 2025-01-07 14:36:05
 */
@Data
@TenantTable
@TableName("oms_order_base_track")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "订单跟踪表")
public class OmsOrderBaseTrackEntity extends BaseEntity<OmsOrderBaseTrackEntity> {


    /**
     * 订单号
     */
    @Schema(description = "订单号")
    private String orderCode;

    /**
     * 订单评审单号
     */
    @Schema(description = "订单评审单号")
    private String orderReviewCode;

    /**
     * 订单跟踪单号
     */
    @Schema(description = "订单跟踪单号")
    private String orderTrackCode;

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
     * 订单状态 1待评审 2评审完成 3 已下发
     */
    @Schema(description = "订单状态 1待评审 2评审完成 3 已下发")
    private String orderState;

    /**
     * 工厂编码
     */
    @Schema(description = "工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

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

    @TableField(exist = false)
    @Schema(description = "订单变更提交永久幂等请求号")
    private String changeRequestId;


    /**
     * 生产计划信息(已下发)
     */
    @Schema(description = "生产计划信息(已下发)")
    @TableField(exist = false)
    private OmsOrderMesInfoDto mesInfo;

    /**
     * 交付信息详情列表
     */
    @Schema(description = "交付信息详情列表")
    @TableField(exist = false)
    private List<OmsOrderDetailsEntity> orderDetailsList;

    /**
     * 日历通知信息
     */
    @Schema(description = "日历通知信息")
    @TableField(exist = false)
    private List<OrderToDeliverCalendarNoticeDto> noticeList;

    /**
     * 发货单信息
     */
    @Schema(description = "发货单信息")
    @TableField(exist = false)
    private List<OrderToDeliverRecordDto> orderToDeliverRecordList;

    /**
     * 生产信息
     */
    @Schema(description = "生产信息")
    @TableField(exist = false)
    private List<OrderToMesDto> mesList;

    /**
     * 历史未交订单
     */
    @Schema(description = "历史未交订单")
    @TableField(exist = false)
    private List<HistoryDeliverOrderDto> historyDeliverOrderDtoList;

}
