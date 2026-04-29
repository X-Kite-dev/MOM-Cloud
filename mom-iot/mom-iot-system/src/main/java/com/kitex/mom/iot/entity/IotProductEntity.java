package com.kitex.mom.iot.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * IoT产品
 *
 * @author system
 * @date 2025-03-12
 */
@Data
@TenantTable
@TableName("iot_product")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "IoT产品")
public class IotProductEntity extends BaseEntity<IotProductEntity> {

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
     * 产品分类ID
     */
    @Schema(description = "产品分类ID")
    private Long categoryId;

    /**
     * 产品类型
     */
    @Schema(description = "产品类型")
    private String productType;

    /**
     * 产品描述
     */
    @Schema(description = "产品描述")
    private String description;


    /**
     * 数据格式
     */
    @Schema(description = "数据格式")
    private String dataFormat;


    /**
     * 状态(0-禁用 1-启用)
     */
    @Schema(description = "状态(0-禁用 1-启用)")
    private Integer status;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

    /**
     * 分类名称(非数据库字段)
     */
    @TableField(exist = false)
    @Schema(description = "分类名称")
    private String categoryName;


    @Schema(description = "网关ID")
    private String gatewayId;
} 