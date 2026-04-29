package com.kitex.mom.tpm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class SparePartsReplacementDto {


    @Schema(description = "总更换数量")
    private BigDecimal totalReplacement;

    @Schema(description = "总花费")
    private BigDecimal totalCost;

    @Schema(description = "明细")
    private List<RankingDto> ranking;


}
