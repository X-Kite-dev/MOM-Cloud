package com.kitex.mom.mdm.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

/**
 * ECN只读引用事实；KEEP_EXISTING不改写远端计划/工单或其BOM快照。
 */
public class MdmBomImpact {
    @Data
    public static class Query {
        private String factoryCode;
        private List<Long> bomIds;
    }

    @Data
    @lombok.EqualsAndHashCode(callSuper = true)
    public static class PageQuery extends Query {
        private String domain;
        private Long afterId;
        private Long upperId;
    }

    @Data
    @Accessors(chain = true)
    public static class Batch {
        private String domain;
        private String factoryCode;
        private Long upperId;
        private Long nextId;
        private Boolean hasMore;
        private Integer scannedCount;
        private List<Row> rows;
    }

    @Data
    @Accessors(chain = true)
    public static class Row {
        private String domain;
        private Long id;
        private String number;
        private String status;
        private String factoryCode;
        private String evidenceJson;
        private String matchKind;
        private List<String> matchedBomIds;
    }
}
