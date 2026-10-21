package com.kitex.mom.oms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 全工厂订单履约风险工作台查询条件。 */
@Data
@Schema(description = "订单履约风险工作台查询条件")
public class OmsFulfillmentRiskQuery {
    private String orderCode;
    private String productCode;
    private String riskCode;
    private String severity;
    private String status;
    private String ownerCode;
    private Boolean activeOnly;
    private Boolean unassignedOnly;
    private Boolean overdueOnly;
}
