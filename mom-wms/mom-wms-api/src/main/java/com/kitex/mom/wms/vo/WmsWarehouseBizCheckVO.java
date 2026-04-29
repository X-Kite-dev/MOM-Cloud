package com.kitex.mom.wms.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

/**
 * 仓库业务选择
 *
 * @author kitex
 * @date 2025/1/13 18:07
 */
@Data
@Accessors(chain = true)
@Schema(description = "仓库业务选择")
public class WmsWarehouseBizCheckVO {
    /**
     * 仓库编码
     */
    @Schema(description = "仓库编码")
    private String warehouseCode;
    /**
     * 选择项
     */
    @Schema(description = "选择项")
    private List<String> bizs;


    /**
     * 批次配置项
     */
    @Schema(description = "批次配置项")
    private Integer isBatch;

    /**
     * 容器配置项
     */
    @Schema(description = "容器配置项")
    private Integer isContainer;

}
