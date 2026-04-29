package com.kitex.mom.wms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 库内移动查询DTO
 *
 * @author kitex
 * @date 2024-12-31 11:16:32
 */
@Data
@Schema(description = "库内移动查询DTO")
public class WmsMaterialMoveSiteQueryDTO {

    /**
     * 物料编码
     */
    @Schema(description = "物料编码")
    private String matCode;

    /**
     * 物料名称
     */
    @Schema(description = "物料名称")
    private String matName;

    /**
     * 仓库编码
     */
    @Schema(description = "仓库编码")
    private String warehouseCode;

    /**
     * 新库位编码
     */
    @Schema(description = "新库位编码")
    private String newSiteCode;

    /**
     * 新库位名称
     */
    @Schema(description = "新库位名称")
    private String newSiteName;

    /**
     * 旧库位编码
     */
    @Schema(description = "旧库位编码")
    private String oldSiteCode;

    /**
     * 旧库位名称
     */
    @Schema(description = "旧库位名称")
    private String oldSiteName;
}