package com.kitex.mom.wms.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 仓库业务选择-App-选择项
 *
 * @author kitex
 * @date 2025/1/13 18:07
 */
@Data
@Accessors(chain = true)
@Schema(description = "仓库业务选择-App-选择项")
public class WmsWarehouseBizCheckAppItemVO {
    /**
     * 代码
     */
    @Schema(description = "代码")
    private String code;
    /**
     * 名称
     */
    @Schema(description = "名称")
    private String name;
    /**
     * 路径
     */
    @Schema(description = "路径")
    private String path;
}
