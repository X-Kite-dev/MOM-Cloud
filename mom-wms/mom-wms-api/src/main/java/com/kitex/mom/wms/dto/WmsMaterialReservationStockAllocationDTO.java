package com.kitex.mom.wms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

@Data
@Accessors(chain = true)
@Schema(description = "PLAN库存Pegging指定的WMS库存行分配")
public class WmsMaterialReservationStockAllocationDTO {
    private Long stockId;
    private BigDecimal allocatedNum;
    private Integer allocationSequence;
}
