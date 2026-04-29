package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 根据产品编码和工位编码查询工单信息参数
 *
 * @author kitex
 * @since 2025-05-06
 */
@Data
@Schema(description = "根据产品编码和工位编码查询工单信息参数")
public class MesTaskQueryByProductWorksiteDTO {

    @Schema(description = "产品编码", required = true)
    private String productCode;

    @Schema(description = "工位编码", required = true)
    private String worksiteCode;

    @Schema(description = "车间编码")
    private String workshopCode;

    @Schema(description = "班次编码")
    private String shiftCode;
} 