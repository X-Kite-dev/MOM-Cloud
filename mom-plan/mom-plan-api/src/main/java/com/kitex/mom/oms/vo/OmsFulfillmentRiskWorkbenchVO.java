package com.kitex.mom.oms.vo;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.mom.oms.entity.OmsFulfillmentRiskCaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/** 工作台汇总与明细采用同一筛选口径，避免卡片和分页口径漂移。 */
@Data
@Accessors(chain = true)
@Schema(description = "订单履约风险工作台")
public class OmsFulfillmentRiskWorkbenchVO {
    private long totalCount;
    private long activeCount;
    private long criticalCount;
    private long unassignedCount;
    private long overdueCount;
    private long pendingCloseCount;
    private Page<OmsFulfillmentRiskCaseEntity> page;
}
