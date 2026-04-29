package com.kitex.mom.iot.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseTreeEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * IoT产品分类
 *
 * @author system
 * @date 2025-03-12
 */
@Data
@TenantTable
@TableName("iot_product_category")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "IoT产品分类")
public class IotProductCategoryEntity extends BaseTreeEntity<IotProductCategoryEntity> {

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
     * 子分类列表(非数据库字段)
     */
    @TableField(exist = false)
    @Schema(description = "子分类列表")
    private List<IotProductCategoryEntity> children;

    /**
     * 产品数量(非数据库字段)
     */
    @TableField(exist = false)
    @Schema(description = "产品数量")
    private Integer productCount;
} 