package com.kitex.mom.mes.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 物料来源只读下钻；不产生预留、绑定或再次扣料许可。
 */
public final class MesMaterialSourceDTO {
    private MesMaterialSourceDTO() {
    }

    @Data
    @Accessors(chain = true)
    public static class Query {
        private String arrangeId;
        private String sourceKind;
        private String sourceId;
        private String requirementMaterialCode;
        private Integer lineCurrent;
        private Integer lineSize;
    }

    @Data
    @Accessors(chain = true)
    public static class Result {
        private String contractVersion = "MES_MATERIAL_TRACE_V1";
        private String tenantId;
        private String factoryCode;
        private LocalDateTime observedAt;
        private String arrangeId;
        private String arrangeCode;
        private String taskCode;
        private String produceTaskCode;
        private String sourceKind;
        private String sourceId;
        private String requirementMaterialCode;
        private Map<String, Object> source;
        private Map<String, Object> lineage;
        private List<String> dataGaps;
    }
}
