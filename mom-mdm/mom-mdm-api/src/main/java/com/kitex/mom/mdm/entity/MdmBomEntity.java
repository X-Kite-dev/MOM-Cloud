package com.kitex.mom.mdm.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.util.TenantTable;
import com.kitex.mom.mdm.base.BusinessEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * BOM
 *
 * @author kitex
 * @date 2024-10-30 14:45:14
 */
@Data
@TenantTable
@TableName("mdm_bom")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "BOM")
public class MdmBomEntity extends BusinessEntity<MdmBomEntity> {

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


}
