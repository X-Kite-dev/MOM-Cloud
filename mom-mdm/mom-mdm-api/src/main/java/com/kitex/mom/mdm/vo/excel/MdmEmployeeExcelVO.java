package com.kitex.mom.mdm.vo.excel;

import com.alibaba.excel.annotation.ExcelIgnore;
import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import com.kitex.common.excel.annotation.ExcelLine;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;

/**
 * 员工excel
 *
 * @author kitex
 * @date 2025-01-20 10:00:00
 */
@Data
@ColumnWidth(30)
@Schema(description = "员工excel")
public class MdmEmployeeExcelVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 导入回显行号
     */
    @ExcelIgnore
    @ExcelLine
    private Long lineNum;

    /**
     * 工号
     */
    @NotBlank(message = "工号不能为空")
    @ExcelProperty(value = "工号")
    private String employeeCode;

    /**
     * 姓名
     */
    @NotBlank(message = "姓名不能为空")
    @ExcelProperty(value = "姓名")
    private String name;

    /**
     * 性别
     */
    @NotBlank(message = "性别不能为空")
    @ExcelProperty(value = "性别")
    private String gender;

    /**
     * 入职时间
     */
    @NotNull(message = "入职时间不能为空")
    @ExcelProperty(value = "入职时间")
    private LocalDate hireDate;

    /**
     * 手机号
     */
    @ExcelProperty(value = "手机号")
    private String phone;

    /**
     * 邮箱
     */
    @ExcelProperty(value = "邮箱")
    private String email;

    /**
     * 身份证号
     */
    @ExcelProperty(value = "身份证号")
    private String idCard;

    /**
     * 部门
     */
    @ExcelProperty(value = "部门")
    private String department;

    /**
     * 职位
     */
    @ExcelProperty(value = "职位")
    private String position;

    /**
     * 状态
     */
    @ExcelProperty(value = "状态")
    private String status;

    /**
     * 备注
     */
    @ExcelProperty(value = "备注")
    private String remark;

}