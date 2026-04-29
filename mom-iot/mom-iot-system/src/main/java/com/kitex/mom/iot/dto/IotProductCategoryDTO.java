package com.kitex.mom.iot.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * IoT产品分类DTO
 *
 * @author system
 * @date 2025-03-12
 */
@Data
@Schema(description = "IoT产品分类DTO")
public class IotProductCategoryDTO {

    /**
     * 主键ID
     */
    @Schema(description = "主键ID")
    private Long id;

    /**
     * 分类名称
     */
    @Schema(description = "分类名称")
    private String categoryName;

    /**
     * 分类编码
     */
    @Schema(description = "分类编码")
    private String categoryCode;

    /**
     * 父分类ID，0表示顶级分类
     */
    @Schema(description = "父分类ID")
    private Long parentId;

    /**
     * 分类描述
     */
    @Schema(description = "分类描述")
    private String description;

    /**
     * 排序
     */
    @Schema(description = "排序")
    private Integer sort;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    private String factoryCode;
} 