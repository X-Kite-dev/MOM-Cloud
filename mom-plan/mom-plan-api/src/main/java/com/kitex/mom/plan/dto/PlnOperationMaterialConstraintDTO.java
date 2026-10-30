package com.kitex.mom.plan.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 工序投料汇合后的物料开工下界。
 */
@Data
@Accessors(chain = true)
public class PlnOperationMaterialConstraintDTO {
    private String routeCode;
    /**
     * null表示旧主工序合同；新快照冻结真实稳定节点。
     */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private String sourceRouteNodeCode;
    private String processCode;
    private String processName;
    private LocalDate materialAvailableDate;
    private LocalDateTime materialAvailableTime;
    private String materialTimePrecision;
    private String status;
    private Boolean hardBlocked;
    private String summary;
    private List<String> materialCodes;
    private List<String> bomPaths;
    private String evidenceFingerprint;
}
