package com.kitex.mom.oms.dto;

import com.kitex.common.core.entity.BaseEntity;
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
public class OmsOrderBaseTrackPageDto extends BaseEntity<OmsOrderBaseTrackPageDto> {


    /**
     * 主表id
     */
    @Schema(description = "主表id")
    private String mainId;

    /**
     * 订单号
     */
    @Schema(description = "订单号")
    private String orderCode;

    /**
     * 评审单号
     */
    @Schema(description = "评审单号")
    private String orderReviewCode;

    /**
     * 跟踪单号
     */
    @Schema(description = "跟踪单号")
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
     * 规格型号
     */
    @Schema(description = "规格型号")
    private String productUnit;

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
     * 明细状态
     */
    @Schema(description = "明细状态")
    private String state;

    /**
     * 订单状态 0 新建 1 待评审 2评审完成
     */
    @Schema(description = "订单状态 0 新建 1 待评审 2评审完成")
    private String orderState;

    /**
     * 变更状态
     */
    @Schema(description = "变更状态")
    private String changeSign;

    /**
     * 交付数量
     */
    @Schema(description = "交付数量")
    private BigDecimal deliverNum;

    /**
     * 交付时间
     */
    @Schema(description = "交付时间")
    private LocalDate deliverTime;

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
     * 工厂编码
     */
    @Schema(description = "工厂编码")
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

    /**
     * 工厂编码列表
     */
    @Schema(description = "工厂编码列表")
    private List<Long> orgIds;

}