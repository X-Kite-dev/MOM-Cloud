package com.kitex.mom.common.barcode.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 条码模板业务属性
 */
@Data
public class BarcodeBusinessFieldReq {

    @Schema(description = "id")
    private Long id;

    @Schema(description = "条码规则ID")
    private Long barcodeRuleId;

    @Schema(description = "业务名称")
    private String name;

    @Schema(description = "业务标识")
    private String value;

}
