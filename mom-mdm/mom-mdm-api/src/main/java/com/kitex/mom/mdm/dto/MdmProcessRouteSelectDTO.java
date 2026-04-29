package com.kitex.mom.mdm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 工艺路线传输DTO
 *
 * @author kitex
 * @date 2025-03-04
 */
@Data
public class MdmProcessRouteSelectDTO {

    /**
     * ID
     */
    @Schema(description = "ID")
    private Long id;

    /**
     * 车间编码
     */
    @Schema(description = "车间编码")
    private String workshopCode;

    /**
     * 车间名称
     */
    @Schema(description = "车间名称")
    private String workshopName;

    /**
     * 工艺路线编码
     */
    @Schema(description = "工艺路线编码")
    private String routeCode;

    /**
     * 工艺路线名称
     */
    @Schema(description = "工艺路线名称")
    private String routeName;

    /**
     * 状态
     */
    @Schema(description = "状态")
    private Boolean status;

    /**
     * 加工车间
     */
    @Schema(description = "加工车间")
    private Long processingWorkshop;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    private String factoryCode;

    private String productCode;

    private String productName;

}