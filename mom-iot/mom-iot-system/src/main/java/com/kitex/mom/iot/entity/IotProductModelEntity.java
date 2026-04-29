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
 * IoT产品数据模型
 *
 * @author system
 * @date 2025-03-12
 */
@Data
@TenantTable
@TableName("iot_product_model")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "IoT产品数据模型")
public class IotProductModelEntity extends BaseEntity<IotProductModelEntity> {

    /**
     * 产品ID
     */
    @Schema(description = "产品ID")
    private Long productId;

    /**
     * 功能类型(property-属性 service-服务 event-事件)
     */
    @Schema(description = "功能类型")
    private String funcType;

    /**
     * 标识符
     */
    @Schema(description = "标识符")
    private String identifier;

    /**
     * 名称
     */
    @Schema(description = "名称")
    private String name;

    /**
     * 数据类型
     */
    @Schema(description = "数据类型")
    private String dataType;

    /**
     * 数据定义(JSON格式)
     */
    @Schema(description = "数据定义")
    private String dataSpecs;

    /**
     * 是否必须
     */
    @Schema(description = "是否必须")
    private Boolean required;

    /**
     * 描述
     */
    @Schema(description = "描述")
    private String description;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
} 