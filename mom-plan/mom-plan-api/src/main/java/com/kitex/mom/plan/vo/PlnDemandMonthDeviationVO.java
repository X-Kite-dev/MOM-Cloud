package com.kitex.mom.plan.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Schema(description = "月需求偏差分析")
public class PlnDemandMonthDeviationVO {

    @Schema(description = "工厂编码")
    private String factoryCode;

    @Schema(description = "客户编码")
    private String customerCode;

    @Schema(description = "客户名称")
    private String customerName;

    @Schema(description = "产品编码")
    private String productCode;

    @Schema(description = "产品名称")
    private String productName;

    @Schema(description = "预测需求")
    private BigDecimal forecastNum;

    @Schema(description = "确认需求")
    private BigDecimal confirmedNum;

    @Schema(description = "可执行需求")
    private BigDecimal executableNum;

    @Schema(description = "已下推执行量")
    private BigDecimal executedNum;

    @Schema(description = "确认-预测")
    private BigDecimal diffConfirmedForecast;

    @Schema(description = "执行-确认")
    private BigDecimal diffExecutedConfirmed;
}
