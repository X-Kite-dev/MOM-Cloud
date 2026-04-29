package com.kitex.mom.pms.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 采购计划VO
 *
 * @author kitex
 * @date 2025-02-08 16:07:45
 */
@Data
@Schema(description = "采购计划VO")
public class PmsPlanVO {

    /**
     * 物料编码
     */
    @Schema(description = "物料编码")
    private String matCode;

    /**
     * 物料类型
     */
    @Schema(description = "物料类型")
    private String matType;

    /**
     * 到货数量
     */
    @Schema(description = "到货数量")
    private Double planArrivalNum;
}