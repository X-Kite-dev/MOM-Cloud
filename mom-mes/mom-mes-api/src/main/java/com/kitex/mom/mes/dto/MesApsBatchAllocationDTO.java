package com.kitex.mom.mes.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

@Data
@Accessors(chain = true)
public class MesApsBatchAllocationDTO {
    private Integer allocationSeq;
    private Long issueId;
    private String issueNo;
    private BigDecimal issueQuantitySnapshot;
    private BigDecimal allocatedQuantity;
}
