package com.kitex.mom.tpm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class RankingDto {

    @Schema(description = "数量")
    private String equipmentCode;

    @Schema(description = "数量")
    private String equipmentName;

    @Schema(description = "数量")
    private BigDecimal quantity;

    @Schema(description = "花费")
    private BigDecimal amount;
}
