package com.kitex.mom.plan.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * OMS权威需求接收前的PLAN参数；不接受客户、产品、数量、交期或来源身份变更。
 */
public final class PlnDemandPlanningParametersDTO {
    private PlnDemandPlanningParametersDTO() {
    }

    @Data
    public static class Snapshot {
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        private Long demandId;
        private String demandNo;
        private String saleOrderCode;
        private String productCode;
        private String productName;
        private String productUnit;
        private BigDecimal needNum;
        private LocalDate planEndDate;
        private String demandWarehouseCode;
        private String demandWarehouseName;
        private Integer mrpPriority;
        private String evidenceFingerprint;
    }

    @Data
    public static class UpdateCommand {
        @NotNull
        private Long demandId;
        @NotBlank
        @Size(max = 128)
        private String demandWarehouseCode;
        @NotNull
        @Min(1)
        @Max(999)
        private Integer mrpPriority;
        @NotBlank
        @Pattern(regexp = "[a-f0-9]{64}")
        private String expectedEvidenceFingerprint;
    }
}
