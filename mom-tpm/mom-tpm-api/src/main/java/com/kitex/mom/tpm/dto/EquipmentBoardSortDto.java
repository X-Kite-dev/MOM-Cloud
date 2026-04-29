package com.kitex.mom.tpm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

@Data
public class EquipmentBoardSortDto {

    @Schema(description = "编码")
    private String code;

    @Schema(description = "名称")
    private String name;

    @Schema(description = "数量")
    private Integer value;

    @Schema(description = "占比")
    private BigDecimal percentage;


    public EquipmentBoardSortDto(String key, String name, int intValue, int size) {
        this.code = key;
        this.name = name;
        this.value = intValue;
        this.percentage = new BigDecimal(intValue).divide(new BigDecimal(size), new MathContext(2, RoundingMode.HALF_UP)).multiply(BigDecimal.valueOf(100));
    }
}
