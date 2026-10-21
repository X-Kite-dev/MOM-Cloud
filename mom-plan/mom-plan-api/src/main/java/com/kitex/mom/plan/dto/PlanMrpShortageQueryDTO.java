package com.kitex.mom.plan.dto;

import lombok.Data;

@Data
public class PlanMrpShortageQueryDTO {
    private String planDemandNo;
    private String saleOrderCode;
    private String factoryCode;
    /** Agent冻结工具契约版本；空值使用当前版本。 */
    private Integer supplyEvidenceVersion;
}
