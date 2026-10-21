package com.kitex.mom.mdm.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class MdmToolingUsageResultDTO {
    private String toolingCode;
    private BigDecimal usage;
    private BigDecimal usedLife;
    private BigDecimal remainingLife;
    private Boolean warning;
    private Boolean duplicated;
}
