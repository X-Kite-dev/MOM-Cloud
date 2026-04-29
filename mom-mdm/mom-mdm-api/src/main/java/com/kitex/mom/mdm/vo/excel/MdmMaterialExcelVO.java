package com.kitex.mom.mdm.vo.excel;

import com.alibaba.excel.annotation.ExcelIgnore;
import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import com.kitex.common.excel.annotation.ExcelLine;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 物料excel 对应的实体
 *
 * @author kitex
 * @date 2024-11-01 15:18:24
 */
@Data
@ColumnWidth(30)
public class MdmMaterialExcelVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 导入时候回显行号
     */
    @ExcelLine
    @ExcelIgnore
    private Long lineNum;

    /**
     * 物料名称
     */
    @NotBlank(message = "物料名称不能为空")
    @ExcelProperty("物料名称")
    private String materialName;

    /**
     * 物料编码
     */
    @NotBlank(message = "物料编码不能为空")
    @ExcelProperty("物料编码")
    private String materialCode;

    /**
     * 物料型态
     */
    @NotBlank(message = "物料型态不能为空")
    @ExcelProperty("物料型态(产品/原材料/半成品/副产品)")
    private String materialType;

    /**
     * 物料属性
     */
    @NotBlank(message = "物料属性不能为空")
    @ExcelProperty("物料属性(采购/委外/自制)")
    private String materialAttribute;

    /**
     * 基本单位
     */
    @NotBlank(message = "基本单位不能为空")
    @ExcelProperty("基本单位")
    private String basicUnit;

    /**
     * 分组编码
     */
    @ExcelProperty("物料分组编码")
    private String groupCode;

    /**
     * 物料分组名称
     */
    @NotBlank(message = "物料分组名称不能为空")
    @ExcelProperty("物料分组名称")
    private String groupName;

    /**
     * 规格型号
     */
    @ExcelProperty("规格型号")
    private String specificationModel;

    /**
     * 安全库存
     */
    @Schema(description = "最小安全库存")
    @ExcelProperty("最小安全库存")
    private BigDecimal minStock;

    /**
     * 安全库存
     */
    @Schema(description = "最大安全库存")
    @ExcelProperty("最大安全库存")
    private BigDecimal maxStock;

}
