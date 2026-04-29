package com.kitex.mom.mdm.vo.excel;

import com.alibaba.excel.annotation.ExcelIgnore;
import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import com.kitex.common.excel.annotation.ExcelLine;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * @program: mom-cloud
 * @description: 车间表Excel数据类
 * @author: qinghuan
 * @create: 2024-10-30 16:41
 **/
@Data
@ColumnWidth(30)
@Schema(description = "车间Excel数据类")
public class MdmWorkshopExcelVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 行号
     */
    @ExcelIgnore
    @ExcelLine
    private Long lineNum;

    /**
     * 车间编码
     */
    @NotBlank(message = "车间编码不能为空")
    @ExcelProperty(value = "车间编码")
    private String code;

    /**
     * 车间名称
     */
    @NotBlank(message = "车间名称不能为空")
    @ExcelProperty(value = "车间名称")
    private String name;

    /**
     * 厂房编码
     */
    @ExcelProperty("所属厂房编码")
    private String factoryCode;

    /**
     * 厂房名称
     */
    @NotBlank(message = "所属厂房名称不能为空")
    @ExcelProperty("所属厂房名称")
    private String factoryName;

    /**
     * 状态
     */
    @ExcelProperty(value = "状态")
    private String status;

    /**
     * 负责人
     */
    @ExcelProperty(value = "负责人")
    private String superintendent;

    /**
     * 车间描述
     */
    @ExcelProperty(value = "车间描述")
    private String description;

}
