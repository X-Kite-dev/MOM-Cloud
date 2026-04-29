package com.kitex.mom.iot.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * IoT产品数据模型DTO
 *
 * @author system
 * @date 2025-03-12
 */
@Data
@Schema(description = "IoT产品数据模型DTO")
public class IotProductModelDTO {

    /**
     * ID
     */
    @Schema(description = "ID")
    private Long id;

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
    private String factoryCode;
} 