package com.kitex.mom.mdm.vo.excel;

import com.alibaba.excel.annotation.ExcelIgnore;
import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import com.kitex.common.excel.annotation.ExcelLine;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * @program: mom-cloud
 * @description: 客户excel数据类
 * @author: qinghuan
 * @create: 2024-10-28 09:48
 **/
@Data
@ColumnWidth(30)
@Schema(description = "客户excel数据类")
public class MdmCustomerExcelVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 行号
     */
    @ExcelIgnore
    @ExcelLine
    private Long lineNum;

    /**
     * 客户编码
     */
    @ExcelProperty(value = "客户编码")
    @Schema(description = "客户编码")
    private String customerCode;

    /**
     * 客户名称
     */
    @ExcelProperty(value = "客户名称")
    @Schema(description = "客户名称")
    private String customerName;

    /**
     * 电话
     */
    @ExcelProperty(value = "电话")
    @Schema(description = "电话")
    private String phone;

    /**
     * 联系人
     */
    @ExcelProperty(value = "联系人")
    @Schema(description = "联系人")
    private String contactPerson;

    /**
     * 本地仓库
     */
    @ExcelProperty(value = "本地仓库")
    @Schema(description = "本地仓库")
    private String localWarehouse;

    /**
     * 三方仓库
     */
    @ExcelProperty(value = "三方仓库")
    @Schema(description = "三方仓库")
    private String thirdPartyWarehouse;

    /**
     * 信用等级
     */
    @ExcelProperty(value = "信用等级")
    @Schema(description = "信用等级")
    private String creditRating;

    /**
     * 客户来源
     */
    @ExcelProperty(value = "客户来源")
    @Schema(description = "客户来源")
    private String customerSource;

    /**
     * 状态 (1=启用, 0=禁用)
     */
    @ExcelProperty(value = "状态 (1=启用, 0=禁用) ")
    @Schema(description = "状态 (1=启用, 0=禁用)")
    private String status;

    /**
     * 备注
     */
    @ExcelProperty(value = "备注")
    @Schema(description = "备注")
    private String remarks;

}
