package com.kitex.mom.wms.dto;

import lombok.Data;

import java.util.Set;

@Data
public class WmsMaterialReservationStatusQueryDTO {
    private String sourceType;
    private Set<Long> sourceIds;
}
