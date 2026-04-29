package com.kitex.mom.mdm.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 工艺路线树形结构VO
 *
 * @author kitex
 * @date 2025-03-26 08:47:12
 */
@Data
@Schema(description = "工艺路线树形结构VO")
public class MdmProcessRouteTreeVO {

    /**
     * 树key
     */
    @Schema(description = "树key")
    private String id;

    /**
     * 名称
     */
    @Schema(description = "名称")
    private String title;

    /**
     * 产品编码
     */
    @Schema(description = "产品编码")
    private String productCode;

    /**
     * 产品名称
     */
    @Schema(description = "产品名称")
    private String productName;

    /**
     * 产品规格型号
     */
    @Schema(description = "产品规格型号")
    private String productSpec;

    /**
     * 产品单位
     */
    @Schema(description = "产品单位")
    private String productUnit;

    /**
     * 子级
     */
    @Schema(description = "子级")
    private List<MdmProcessRouteChildrenVO> children;

}