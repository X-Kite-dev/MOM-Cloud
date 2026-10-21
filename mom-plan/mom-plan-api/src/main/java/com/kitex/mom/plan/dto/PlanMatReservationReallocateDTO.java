package com.kitex.mom.plan.dto;

import lombok.Data;

@Data
public class PlanMatReservationReallocateDTO {
    private Long mrpId;
    private String requestId;
    private String reason;
}
