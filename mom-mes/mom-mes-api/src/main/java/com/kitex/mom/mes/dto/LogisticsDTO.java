package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 物流与分销
 *
 * @author kitex
 * @since 2025-04-24
 */
@Data
public class LogisticsDTO {

    // ---------- 运输信息 ----------

    @Schema(description = "承运商")
    private String supplierName;

    @Schema(description = "运输车号")
    private String carNo;

    @Schema(description = "发货时间")
    private LocalDateTime startTime;

    @Schema(description = "预计到达")
    private LocalDateTime endTime;

    // ---------- 运输监控 ----------

    @Schema(description = "运输温度")
    private BigDecimal temperature;

    @Schema(description = "运输湿度")
    private BigDecimal humidity;

    @Schema(description = "GPS定位")
    private String gps;

    // ---------- 客户信息 ----------

    @Schema(description = "客户名称")
    private String customerName;

    @Schema(description = "收货地址")
    private String address;

    @Schema(description = "联系人")
    private String concatPerson;

    @Schema(description = "订单号")
    private String orderNo;
}
