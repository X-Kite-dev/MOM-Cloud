package com.kitex.mom.oms.dto;

import com.kitex.common.core.entity.BaseEntity;
import com.kitex.mom.oms.entity.OmsOrderDetailsEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 订单输入
 *
 * @author kitex
 * @date 2025-01-07 14:20:47
 */
@Data
public class OmsOrderBaseReviewPageDto extends BaseEntity<OmsOrderBaseReviewPageDto> {

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
     * 是否变更订单 0 否 1是
     */
    @Schema(description = "订单状态 0 否 1是")
    private String changeType;


    /**
     * 订单数量
     */
    @Schema(description = "订单数量")
    private String orderNum;

    /**
     * 最近交付时间
     */
    @Schema(description = "最近交付时间")
    private LocalDate minDeliverTime;

    /**
     * 成品库存
     */
    @Schema(description = "成品库存")
    private BigDecimal productLiveStockNum;

    /**
     * 已交付数量
     */
    @Schema(description = "未交付数量")
    private BigDecimal unDeliverNum;

    /**
     * 评审意见
     */
    @Schema(description = "评审意见")
    private String remark;

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
     * 交付信息详情列表
     */
    @Schema(description = "交付信息详情列表")
    private List<OmsOrderDetailsEntity> orderDetailsList;

    /**
     * 历史未交订单
     */
    @Schema(description = "历史未交订单")
    private List<HistoryDeliverOrderDto> historyDeliverOrderDtoList;

    /**
     * 工厂编码
     */
    @Schema(description = "工厂编码")
    private String factoryCode;

    /**
     * 工厂编码列表
     */
    @Schema(description = "工厂编码列表")
    private List<Long> orgIds;

}