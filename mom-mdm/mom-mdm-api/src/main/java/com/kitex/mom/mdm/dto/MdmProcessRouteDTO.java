package com.kitex.mom.mdm.dto;

import com.alibaba.excel.annotation.ExcelProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.kitex.mom.mdm.vo.MdmProcessRouteWorkingVO;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 工艺路线传输DTO
 *
 * @author kitex
 * @date 2024-10-31 14:49:27
 */
@Data
public class MdmProcessRouteDTO {

    /**
     * ID
     */
    @Schema(description = "ID")
    private Long id;

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
     * bom版本
     */
    @Schema(description = "bom版本")
    private String bomVersion;

    /**
     * 生产速率(每小时生产个数)
     */
    @ExcelProperty(value = "生产速率(每小时生产个数)")
    private BigDecimal productRate;

    /**
     * 是否默认工艺路线
     */
    @Schema(description = "是否默认工艺路线")
    private Boolean isDefault;

    /**
     * 状态
     */
    @Schema(description = "状态")
    private Boolean status;

    /**
     * 车间名称
     */
    @Schema(description = "车间名称")
    private String workshopName;

    /**
     * 车间编码
     */
    @Schema(description = "车间编码")
    private String workshopCode;

    /**
     * 产线编码
     */
    @Schema(description = "产线编码")
    private String productLineCode;

    /**
     * 产线名称
     */
    @Schema(description = "产线名称")
    private String productLineName;

    /**
     * 备注
     */
    @Schema(description = "备注")
    private String remark;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    private String factoryCode;

    /**
     * 工序快照
     */
    @Schema(description = "工序快照")
    private JsonNode procedureSnapshot;

    /**
     * 工序列表
     */
    @Schema(description = "工序列表")
    private List<MdmProcessRouteWorkingVO> procedureList;

}