package com.kitex.mom.mdm.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class MdmToolingUsageLineDTO {
    private String toolingCode;
    private BigDecimal usage;
}
