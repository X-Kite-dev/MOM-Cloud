package com.kitex.mom.wms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 实时库存动态分组查询DTO
 *
 * @author cascade
 * @date 2025-02-20
 */
@Data
@Schema(description = "实时库存动态分组查询DTO")
public class WmsLiveStockDynamicGroupDTO {

    @Schema(description = "仓库编码")
    private String warehouseCode;

    @Schema(description = "物料编码")
    private String matCode;

    @Schema(description = "物料名称")
    private String matName;

    @Schema(description = "库位编码")
    private List<String> siteCode;

    @Schema(description = "分组字段列表：warehouse_code,mat_code,site_code,mat_batch,supplier_code；为空时返回明细数据")
    private List<String> groupFields;
}
