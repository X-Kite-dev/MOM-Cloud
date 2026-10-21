package com.kitex.mom.wms.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

@Data
@Accessors(chain = true)
public class WmsMaterialReservationResultDTO {
    private String reservationNo;
    private String status;
    private String warehouseCode;
    private String warehouseName;
    private List<WmsMaterialReservationAllocationDTO> allocations;
}
