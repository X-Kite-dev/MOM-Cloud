package com.kitex.mom.pms.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Accessors(chain = true)
public class PmsAgentPurchaseReceiptDTO {
    private String requestId;
    private Long mrpId;
    private Long pmsOrderId;
    private String pmsOrderNo;
    private Long pmsOrderDetailId;
    private String pmsPlanNo;
    private Boolean purchasePlanCreated;
    private String supplierCode;
    private String materialCode;
    private BigDecimal purchaseNum;
    private LocalDate requestedDeliveryDate;
    private String orderStatus;
    private Boolean workflowSubmitted;
}
