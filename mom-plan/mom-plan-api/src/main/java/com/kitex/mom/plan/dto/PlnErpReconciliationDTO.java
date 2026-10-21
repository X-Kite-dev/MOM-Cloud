package com.kitex.mom.plan.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 当前工厂 ERP 投递健康度与待处置清单。 */
@Data
@Accessors(chain = true)
@Schema(description = "ERP投递对账摘要")
public class PlnErpReconciliationDTO {
    private String factoryCode;
    private Integer sampledCount;
    private Integer pendingCount;
    private Integer blockedCount;
    private Integer failedCount;
    private Integer succeededCount;
    private LocalDateTime oldestUnfinishedTime;
    private Map<String, Integer> statusCounts;
    private Map<String, Integer> eventCounts;
    private List<String> attentionRequestIds;
    private LocalDateTime reconciledTime;
}
