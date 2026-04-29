package com.kitex.mom.mdm.dto;

import com.kitex.mom.mdm.entity.MdmBomChildEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * @program: mom-cloud
 * @description: bom数据类
 * @author: qinghuan
 * @create: 2024-10-31 09:23
 **/
@Data
@Schema(description = "bom数据类")
public class MdmBomDTO {

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
     * 产品规格型号
     */
    @Schema(description = "产品规格型号")
    private String productSpec;

    /**
     * 产品单位
     */
    @Schema(description = "产品单位")
    private String unit;

    /**
     * 版本
     */
    @Schema(description = "版本")
    private String version;

    /**
     * 是否默认bom
     */
    @Schema(description = "是否默认bom")
    private Boolean isDefault;

    /**
     * 类型
     */
    @Schema(description = "类型")
    private String type;

    /**
     * 启用 (true=启用, false=禁用)
     */
    @Schema(description = "启用 (true=启用, false=禁用)")
    private Boolean enabled;

    /**
     * 开始日期
     */
    @Schema(description = "开始日期")
    private LocalDate startDate;

    /**
     * 截止日期
     */
    @Schema(description = "截止日期")
    private LocalDate endDate;

    /**
     * 是否拥有子项bom
     */
    @Schema(description = "是否拥有子项bom")
    private Boolean hasChildren;

    /**
     * 物料信息
     */
    @Schema(description = "物料信息")
    private List<MdmBomChildEntity> mdmBomChildList;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    private String factoryCode;

}
