package com.kitex.mom.mdm.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Read-only bounded APS evidence, separate from policy publication authority.
 */
@Data
@Accessors(chain = true)
public class MdmFactoryPlanningApsReferencesDTO {
    public static final int MAX_RECORDS = 50;
    private String draftId;

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long tenantId;

    private String factoryCode;
    private Long total;
    private Integer limit;
    private Boolean truncated;
    private LocalDateTime observedAt;
    private List<Reference> records;

    @Data
    @Accessors(chain = true)
    public static class Reference {
        private String sourceType;
        private String sourceId;
        private String sourceNo;
        private String planId;
        private String planNo;
        private String productCode;
        private String planStatus;
        private String taskId;
        private String taskNo;
        private String taskStatus;
        private String sourceStatus;
        private Boolean sourceDeleted;
        private Boolean relationIncomplete;
    }
}
