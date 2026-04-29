package com.kitex.mom.iot.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * IoT产品DTO
 *
 * @author system
 * @date 2025-03-12
 */
@Data
@Schema(description = "IoT产品DTO")
public class IotProductDTO {

    /**
     * ID
     */
    @Schema(description = "ID")
    private Long id;

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
    private String factoryCode;

    /**
     * 产品分类ID
     */
    @Schema(description = "产品分类ID")
    private Long categoryId;


    /**
     * 网关ID
     */
    @Schema(description = "网关ID")
    private String gatewayId;
} 