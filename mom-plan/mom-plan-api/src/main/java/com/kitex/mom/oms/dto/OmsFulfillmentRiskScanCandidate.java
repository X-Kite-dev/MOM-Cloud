package com.kitex.mom.oms.dto;

import lombok.Data;

@Data
public class OmsFulfillmentRiskScanCandidate {
    private Long orderDetailId;
    private Long tenantId;
    private String factoryCode;
    private String orderCode;
    private String productCode;
}
