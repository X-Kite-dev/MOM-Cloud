package com.kitex.mom.pms.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
public class PmsSupplyCommitmentEvidenceDTO {
    private String commitmentNo;
    private Integer versionNo;
    private BigDecimal committedNum;
    private LocalDate confirmedDeliveryDate;
    private LocalDateTime confirmedDeliveryTime;
    private String deliveryTimePrecision;
    private String sourceType;
    private String evidenceRef;
}
