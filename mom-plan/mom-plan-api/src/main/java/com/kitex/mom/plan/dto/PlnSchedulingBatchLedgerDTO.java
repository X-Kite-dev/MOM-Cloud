package com.kitex.mom.plan.dto;

import com.kitex.mom.plan.entity.PlnSchedulingBatchAllocationEntity;
import com.kitex.mom.plan.entity.PlnSchedulingBatchEntity;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.util.List;

@Data
@Accessors(chain = true)
public class PlnSchedulingBatchLedgerDTO {
    private Long taskId;
    private BigDecimal sourceQuantity;
    private BigDecimal allocatedQuantity;
    private BigDecimal batchQuantity;
    private Boolean quantityBalanced;
    private String quantityFingerprint;
    private List<Batch> batches;

    @Data
    @Accessors(chain = true)
    public static class Batch {
        private PlnSchedulingBatchEntity header;
        private List<PlnSchedulingBatchAllocationEntity> allocations;
    }
}
