package com.kitex.mom.plan.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
@Accessors(chain = true)
public class PlnSchedulingBatchConfigureDTO {
    private Long taskId;
    private String requestId;
    private String expectedQuantityFingerprint;
    private String reason;
    private List<Batch> batches;

    @Data
    @Accessors(chain = true)
    public static class Batch {
        private LocalDate requestedStartDate;
        private List<Allocation> allocations;
    }

    @Data
    @Accessors(chain = true)
    public static class Allocation {
        private Long issueId;
        private BigDecimal quantity;
    }
}
