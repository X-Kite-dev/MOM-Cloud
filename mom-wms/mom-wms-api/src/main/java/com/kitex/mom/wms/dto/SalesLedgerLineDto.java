package com.kitex.mom.wms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;


@Data
public class SalesLedgerLineDto {


    @Schema(name = "时间 M月")
    private String time;

    @Schema(name = "数量")
    private BigDecimal deliveredNum;

}
