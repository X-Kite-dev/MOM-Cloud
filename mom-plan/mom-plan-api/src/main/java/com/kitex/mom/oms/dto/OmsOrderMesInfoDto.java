package com.kitex.mom.oms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class OmsOrderMesInfoDto {

    @Schema(description = "计划总数")
    private Integer planNum;

    @Schema(description = "未生产总数")
    private BigDecimal unProduceNum;

    @Schema(description = "库存总数")
    private BigDecimal productLiveStockNum;

    @Schema(description = "平均每天生产总数")
    private BigDecimal dayAvgProduceNum;


}
