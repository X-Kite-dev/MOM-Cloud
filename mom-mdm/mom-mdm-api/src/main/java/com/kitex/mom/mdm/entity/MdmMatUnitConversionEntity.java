package com.kitex.mom.mdm.entity;

import com.alibaba.excel.annotation.ExcelIgnore;
import com.alibaba.excel.annotation.ExcelProperty;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 物料单位转换规则
 *
 * @author kitex
 * @date 2024-12-12 10:59:24
 */
@Data
@TenantTable
@TableName("mdm_mat_unit_conversion")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "物料单位")
public class MdmMatUnitConversionEntity extends BaseEntity<MdmMatUnitConversionEntity> {

    /**
     * 物料编码
     */
    @ExcelProperty("物料编码")
    @Schema(description = "物料编码")
    private String matCode;

    /**
     * 物料名称
     */
    @ExcelProperty("物料名称")
    @Schema(description = "物料名称")
    private String matName;

    /**
     * 规格型号
     */
    @ExcelProperty("规格型号")
    @Schema(description = "规格型号")
    private String matSpec;

    /**
     * 非标准单位
     */
    @ExcelProperty("非标准单位")
    @Schema(description = "非标准单位")
    private String nonStandardUnit;

    /**
     * 比率
     */
    @ExcelProperty("比率")
    @Schema(description = "比率")
    private String ratio;

    /**
     * 标准单位
     */
    @ExcelProperty("标准单位")
    @Schema(description = "标准单位")
    private String standardUnit;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @ExcelIgnore
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

}