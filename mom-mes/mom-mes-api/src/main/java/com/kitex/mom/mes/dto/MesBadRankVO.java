package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 工序不良率排名
 *
 * @author kitex
 * @since 2025-04-07
 */
@Data
public class MesBadRankVO {


    @Schema(description = "工序名称")
    private String name;

    @Schema(description = "百分比")
    private BigDecimal percentage;
}
