package com.kitex.mom.mdm.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 车间树形结构VO
 *
 * @author kitex
 * @date 2025-05-14 13:47:54
 */
@Data
@Schema(description = "车间树形结构VO")
public class MdmWorkShopTreeVO {

    /**
     * 树key
     */
    @Schema(description = "树key")
    private Long id;

    /**
     * 车间名称
     */
    @Schema(description = "车间名称")
    private String name;

    /**
     * 车间编码
     */
    @Schema(description = "车间编码")
    private String code;

    /**
     * 子级
     //     */
//    @Schema(description = "子级")
//    private List<MdmWorkshopEntity> children;

}