package com.kitex.mom.mdm.vo.excel;

import com.alibaba.excel.annotation.ExcelIgnore;
import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import com.alibaba.excel.annotation.write.style.ContentRowHeight;
import com.alibaba.excel.annotation.write.style.HeadRowHeight;
import com.kitex.common.excel.annotation.ExcelLine;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * 工厂建模Excel导入导出VO
 *
 * @author claude
 * @date 2025-07-30
 */
@Data
@ContentRowHeight(20)
@HeadRowHeight(25)
@ColumnWidth(30)
@Schema(description = "工厂建模Excel导入导出VO")
public class MdmFactoryModelExcelVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 导入时候回显行号
     */
    @ExcelLine
    @ExcelIgnore
    private Long lineNum;

    @ExcelProperty("父节点编码")
    @NotBlank(message = "父节点编码不能为空")
    @Schema(description = "父节点编码")
    private String parentCode;

    @ExcelProperty("节点编码")
    @NotBlank(message = "节点编码不能为空")
    @Schema(description = "节点编码")
    private String code;

    @ExcelProperty("节点名称")
    @NotBlank(message = "节点名称不能为空")
    @Schema(description = "节点名称")
    private String name;

    @ExcelProperty("节点类型")
    @NotBlank(message = "节点类型不能为空")
    @Schema(description = "节点类型")
    private String type;

    @ExcelProperty("排序")
    @Schema(description = "排序")
    private Integer sort;

    @ExcelProperty("状态")
    @Schema(description = "状态 (启用, 禁用)")
    private String status;

    @ExcelProperty("节点描述")
    @ColumnWidth(30)
    @Schema(description = "节点描述")
    private String description;
}