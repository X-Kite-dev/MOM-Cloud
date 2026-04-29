package com.kitex.mom.wms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class SalesBoardLineDto {

    @Schema(name = "发货时间")
    private LocalDate deliverTime;

    @Schema(name = "发货时间 M.dd")
    private String time;

    @Schema(name = "序号")
    private Integer index;

    @Schema(name = "数量")
    private BigDecimal deliveredNum;

}
