package com.kitex.mom.mdm.vo;

import com.kitex.common.core.entity.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 工艺路线子级VO
 *
 * @author kitex
 * @date 2024-10-30 14:36:59
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "工艺路线子级VO")
public class MdmProcessRouteChildrenVO extends BaseEntity<MdmProcessRouteChildrenVO> {

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
     * 标题
     */
    @Schema(description = "标题")
    private String title;

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
     * 规格型号
     */
    @Schema(description = "规格型号")
    private String productSpec;

    /**
     * 产品单位
     */
    @Schema(description = "产品单位")
    private String productUnit;

    /**
     * bom版本
     */
    @Schema(description = "bom版本")
    private String bomVersion;

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
     * 工序快照
     */
    @Schema(description = "工序快照")
    private String procedureSnapshot;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    private String factoryCode;

}