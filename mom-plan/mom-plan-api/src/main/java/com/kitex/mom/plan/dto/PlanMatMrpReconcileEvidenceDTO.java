package com.kitex.mom.plan.dto;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class PlanMatMrpReconcileEvidenceDTO {
    private Long sourceId;
    private String planDemandNo;
    private String requirementMatCode;
    private String demandWarehouseCode;
    private String substituteStatus;
    private String reservationNo;
    private String reservationStatus;
    private String mainReservationNo;
    private String mainReservationStatus;
    private Boolean issued;
    private String factoryCode;
}
