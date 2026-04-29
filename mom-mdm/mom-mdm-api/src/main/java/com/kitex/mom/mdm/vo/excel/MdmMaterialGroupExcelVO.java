package com.kitex.mom.mdm.vo.excel;

import com.alibaba.excel.annotation.ExcelIgnore;
import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import com.kitex.common.excel.annotation.ExcelLine;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * 物料分组excel 对应的实体
 *
 * @author kitex
 * @date 2024-11-04 15:42:37
 */
@Data
@ColumnWidth(30)
public class MdmMaterialGroupExcelVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 导入时候回显行号
     */
    @ExcelLine
    @ExcelIgnore
    private Long lineNum;

    /**
     * 上级编码
     */
    @NotBlank(message = "上级编码不能为空")
    @ExcelProperty("上级编码")
    private String parentCode;

    /**
     * 物料分组编码
     */
    @NotBlank(message = "物料分组编码不能为空")
    @ExcelProperty("物料分组编码")
    private String groupCode;

    /**
     * 物料分组名称
     */
    @NotBlank(message = "物料分组名称不能为空")
    @ExcelProperty("物料分组名称")
    private String groupName;

    /**
     * 排序
     */
    @ExcelProperty("排序")
    private Integer sortOrder;

    /**
     * 描述
     */
    @ExcelProperty("描述")
    private String description;

}
