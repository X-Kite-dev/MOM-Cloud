package com.kitex.mom.iot.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * IoT数据规格DTO
 *
 * @author system
 * @date 2025-03-12
 */
@Data
@Schema(description = "IoT数据规格DTO")
public class IotDataSpecsDTO {

    /**
     * 最小值(数值类型)
     */
    @Schema(description = "最小值")
    private Double min;

    /**
     * 最大值(数值类型)
     */
    @Schema(description = "最大值")
    private Double max;

    /**
     * 步长(数值类型)
     */
    @Schema(description = "步长")
    private Double step;

    /**
     * 单位(数值类型)
     */
    @Schema(description = "单位")
    private String unit;

    /**
     * 最大长度(字符串类型)
     */
    @Schema(description = "最大长度")
    private Integer maxLength;

    /**
     * 枚举项(枚举类型)
     */
    @Schema(description = "枚举项")
    private List<EnumItem> enumItems;

    /**
     * 数组元素类型(数组类型)
     */
    @Schema(description = "数组元素类型")
    private String arrayType;

    /**
     * 数组元素规格(数组类型)
     */
    @Schema(description = "数组元素规格")
    private IotDataSpecsDTO arraySpecs;

    /**
     * 结构体属性(结构体类型)
     */
    @Schema(description = "结构体属性")
    private List<StructProperty> properties;

    /**
     * 枚举项
     */
    @Data
    @Schema(description = "枚举项")
    public static class EnumItem {
        /**
         * 值
         */
        @Schema(description = "值")
        private String value;

        /**
         * 描述
         */
        @Schema(description = "描述")
        private String description;
    }

    /**
     * 结构体属性
     */
    @Data
    @Schema(description = "结构体属性")
    public static class StructProperty {
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
         * 数据规格
         */
        @Schema(description = "数据规格")
        private IotDataSpecsDTO dataSpecs;
    }
} 