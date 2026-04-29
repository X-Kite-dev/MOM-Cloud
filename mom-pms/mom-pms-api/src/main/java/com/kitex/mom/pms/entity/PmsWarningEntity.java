package com.kitex.mom.pms.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * 采购异常表
 *
 * @author kitex
 * @date 2025-01-08 10:29:32
 */
@Data
@TableName("pms_warning")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "采购异常表")
public class PmsWarningEntity extends BaseEntity<PmsWarningEntity> {

    /**
     * 采购计划号
     */
    @Schema(description = "采购计划号")
    private String pmsPlanNo;

    /**
     * 订单号
     */
    @Schema(description = "订单号")
    private String orderNo;

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
     * 物料规格
     */
    @Schema(description = "物料规格")
    private String matSpec;

    /**
     * 采购数量
     */
    @Schema(description = "采购数量")
    private Double pmsNum;

    /**
     * 物料单位
     */
    @Schema(description = "物料单位")
    private String matUnit;

    /**
     * 要货时间
     */
    @Schema(description = "要货时间")
    private LocalDate planDeliveryTime;

    /**
     * 下单数量
     */
    @Schema(description = "下单数量")
    private Double planOrderNum;

    /**
     * 到货数量
     */
    @Schema(description = "到货数量")
    private Double planArrivalNum;

    /**
     * 异常类型（0 延期到货 1 质检不合格）
     */
    @Schema(description = "异常类型（0 延期到货 1 质检不合格）")
    private String type;

    /**
     * 异常原因
     */
    @Schema(description = "异常原因")
    private String reason;

    /**
     * 异常确认状态, true 已确认 false 未确认
     */
    @Schema(description = "异常确认状态, true 已确认 false 未确认")
    private Boolean status;

    /**
     * 处理意见
     */
    @Schema(description = "处理意见")
    private String handlerSuggest;

    /**
     * 工厂编码
     */
    @Schema(description = "工厂编码")
    private String factoryCode;
}