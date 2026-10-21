package com.kitex.mom.wms.dto;

import lombok.Data;

@Data
public class WmsMaterialReservationReleaseDTO {
    private String requestId;
    private String sourceType;
    private Long sourceId;
    private String reason;
}
