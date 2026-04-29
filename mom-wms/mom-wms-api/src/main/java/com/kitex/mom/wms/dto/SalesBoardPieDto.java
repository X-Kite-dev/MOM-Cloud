package com.kitex.mom.wms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class SalesBoardPieDto {

    @Schema(name = "产品编码")
    private String productCode;

    @Schema(name = "产品名称")
    private String productName;

    @Schema(name = "数量")
    private BigDecimal deliveredNum;

}
