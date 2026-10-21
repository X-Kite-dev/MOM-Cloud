package com.kitex.mom.plan.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

@Data
@Accessors(chain = true)
public class PlanAgentTransferReceiptDTO {
    private Long mrpId;
    private Long allocationBillId;
    private String billNo;
    private String allocationState;
    private String requestId;
    private String sourceWarehouseCode;
    private String targetWarehouseCode;
    private String materialCode;
    private BigDecimal transferNum;
}
