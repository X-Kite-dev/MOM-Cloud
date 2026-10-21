package com.kitex.mom.wms.dto;

import lombok.Data;

@Data
public class WmsMaterialReservationReallocateDTO {
    private String requestId;
    private String sourceType;
    private Long sourceId;
    private String reason;
}
