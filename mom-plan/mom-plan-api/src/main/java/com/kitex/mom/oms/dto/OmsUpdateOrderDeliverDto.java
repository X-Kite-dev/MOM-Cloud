package com.kitex.mom.oms.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class OmsUpdateOrderDeliverDto {

    /**
     * 出库单号
     */
    @Schema(description = "出库单号")
    private String wmsBillNo;

    /**
     * 订单编号
     */
    @Schema(description = "订单编号")
    private String orderCode;

    /**
     * 产品编码
     */
    @Schema(description = "产品编码")
    private String productCode;

    /**
     * 发货时间
     */
    @Schema(description = "发货时间")
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd")
    private LocalDate deliverTime;

    /**
     * 操作数量
     */
    @Schema(description = "操作数量")
    private BigDecimal operateNum;

}
