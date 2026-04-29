package com.kitex.mom.oms.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;

@Data
public class HistoryDeliverOrderDto {

    @Schema(description = "订单号")
    private String orderCode;

    @Schema(description = "产品编码")
    private String productCode;

    @Schema(description = "产品名称")
    private String productName;

    @Schema(description = "规格型号")
    private String productSpec;

    @Schema(description = "产品单位")
    private String productUnit;

    @Schema(description = "需交付数量")
    private String deliverNum;

    @Schema(description = "已交付数量")
    private String deliveredNum;

    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd")
    @Schema(description = "交付时间")
    private LocalDate deliverTime;

    @Schema(description = "状态")
    private String state;


}
