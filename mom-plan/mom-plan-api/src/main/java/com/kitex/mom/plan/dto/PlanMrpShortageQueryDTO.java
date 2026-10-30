package com.kitex.mom.plan.dto;

import lombok.Data;

@Data
public class PlanMrpShortageQueryDTO {
    /**
     * 产品需求主键。完整缺料决策Agent必须同时传主键和需求号；普通问数兼容只传需求号。
     */
    private Long planDemandId;

    private String planDemandNo;
    private String saleOrderCode;
    private String factoryCode;
    /**
     * Agent冻结工具契约版本；空值使用当前版本。
     */
    private Integer supplyEvidenceVersion;
}
