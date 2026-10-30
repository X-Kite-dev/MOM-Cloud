package com.kitex.mom.mes.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Read-only original consumption evidence. Quantities are original decimal text, never net balances.
 */
public final class MesMaterialConsumptionTraceDTO {
    private MesMaterialConsumptionTraceDTO() {
    }

    @Data
    @Accessors(chain = true)
    public static class Query {
        private String taskCode;
        private String arrangeCode;
        private String requirementMaterialCode;
        private String actualMaterialCode;
        private String batchNo;
        private String sourceKind;
        private Integer current;
        private Integer size;
    }

    @Data
    @Accessors(chain = true)
    public static class Row {
        private String key;
        private String sourceKind;
        private String sourceId;
        private String sourceTable;
        private String taskCode;
        private String produceTaskCode;
        private String arrangeCode;
        private String feedId;
        private String stockId;
        private String reportId;
        private String reportDomain;
        private String rootId;
        private String requestId;
        private String actualMaterialCode;
        private String requirementMaterialCode;
        private String batchNo;
        private String barcode;
        private String unit;
        private String requirementUnit;
        private String quantity;
        private String requirementQuantity;
        private String sourceStatus;
        private String restorationStatus;
        private LocalDateTime occurredAt;
        private List<String> dataGaps = new ArrayList<>();
    }

    @Data
    @Accessors(chain = true)
    public static class Page {
        private String contractVersion = "MES_MATERIAL_TRACE_V1";
        private String tenantId;
        private String factoryCode;
        private LocalDateTime observedAt;
        private Query query;
        private Integer current;
        private Integer size;
        private long total;
        private List<Row> records = new ArrayList<>();
        private List<String> dataGaps = new ArrayList<>();
    }

    @Data
    @Accessors(chain = true)
    public static class Fact {
        private String tableName;
        private String sourceId;
        private Map<String, String> fields;
    }

    @Data
    @Accessors(chain = true)
    public static class Detail {
        private String contractVersion = "MES_MATERIAL_TRACE_V1";
        private String tenantId;
        private String factoryCode;
        private LocalDateTime observedAt;
        private Row record;
        private Fact originalRoot;
        private List<Fact> attributions = new ArrayList<>();
        private List<Fact> restores = new ArrayList<>();
        private List<Fact> reversals = new ArrayList<>();
        private List<Fact> relatedSources = new ArrayList<>();
        private List<String> dataGaps = new ArrayList<>();
    }
}
