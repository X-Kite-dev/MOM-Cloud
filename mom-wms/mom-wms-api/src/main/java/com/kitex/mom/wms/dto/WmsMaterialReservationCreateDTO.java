package com.kitex.mom.wms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

@Data
@Schema(description = "物料预留创建命令")
public class WmsMaterialReservationCreateDTO {
    private String requestId;
    private String sourceType;
    private Long sourceId;
    private String sourceNo;
    private String factoryCode;
    private String warehouseCode;
    private String warehouseName;
    private String customerCode;
    private LocalDate requiredDate;
    private List<WmsMaterialReservationAllocationDTO> allocations;
}
