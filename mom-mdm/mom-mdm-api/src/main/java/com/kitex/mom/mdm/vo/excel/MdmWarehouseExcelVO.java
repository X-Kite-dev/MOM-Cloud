package com.kitex.mom.mdm.vo.excel;

import com.alibaba.excel.annotation.ExcelIgnore;
import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import com.kitex.common.excel.annotation.ExcelLine;
import lombok.Data;

import java.io.Serializable;

/**
 * @program: mom-cloud
 * @description: 仓库Excel数据类
 * @author: qinghuan
 * @create: 2024-10-28 14:15
 **/
@Data
@ColumnWidth(30)
public class MdmWarehouseExcelVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 导入回显行号
     */
    @ExcelIgnore
    @ExcelLine
    private Long lineNum;

    /**
     * 工厂编码
     */
    @ExcelProperty(value = "工厂编码")
    private String factoryCode;
    /**
     * 仓库编码
     */
    @ExcelProperty(value = "仓库编码")
    private String warehouseCode;

    /**
     * 名称
     */
    @ExcelProperty(value = "仓库名称")
    private String warehouseName;

    /**
     * 仓库类型
     */
    @ExcelProperty(value = "仓库类型")
    private String warehouseType;

    /**
     * 仓库负责人
     */
    @ExcelProperty(value = "仓库负责人")
    private String warehouseManager;

    /**
     * 仓库地址
     */
    @ExcelProperty(value = "仓库地址")
    private String warehouseAddress;

    /**
     * 说明
     */
    @ExcelProperty(value = "说明")
    private String description;

    /**
     * 排序
     */
    @ExcelProperty(value = "排序")
    private Integer sort;

    /**
     * 状态
     */
    @ExcelProperty(value = "状态")
    private String status;

}
