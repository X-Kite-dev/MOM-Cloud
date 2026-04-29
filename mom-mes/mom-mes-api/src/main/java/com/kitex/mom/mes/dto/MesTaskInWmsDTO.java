package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 生产工单入库DTO
 *
 * @author kitex
 * @date 2025-04-29 14:34:51
 */
@Data
@Schema(description = "生产工单入库DTO")
public class MesTaskInWmsDTO {

    /**
     * 工单ID
     */
    @Schema(description = "工单ID")
    private Long id;

    /**
     * 仓库编码
     */
    @Schema(description = "仓库编码")
    private String warehouseCode;

    /**
     * 仓库名称
     */
    @Schema(description = "仓库名称")
    private String warehouseName;

    /**
     * 成品入库量
     */
    @Schema(description = "成品入库量")
    private BigDecimal inWmsNum;

    /**
     * 备注
     */
    @Schema(description = "备注")
    private String remark;

}
