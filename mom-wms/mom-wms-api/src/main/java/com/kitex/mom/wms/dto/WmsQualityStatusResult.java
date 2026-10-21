package com.kitex.mom.wms.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

@Data
@Accessors(chain = true)
public class WmsQualityStatusResult {
    private Long operationId;
    private String holdNo;
    private String action;
    private String status;
    private BigDecimal requestedQuantity;
    private BigDecimal affectedQuantity;
    private String message;
}
