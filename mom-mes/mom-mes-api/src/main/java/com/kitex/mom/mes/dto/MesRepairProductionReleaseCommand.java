package com.kitex.mom.mes.dto;

import lombok.Data;

@Data
public class MesRepairProductionReleaseCommand {
    private Long stopId;
    private String action;
    private String requestId;
    private String expectedFingerprint;
    private Long expectedLastId;
    private String reason;
    private Boolean siteChecked;
    private Boolean equipmentChecked;
    private Boolean materialQualityChecked;
    private Boolean personnelChecked;
}
