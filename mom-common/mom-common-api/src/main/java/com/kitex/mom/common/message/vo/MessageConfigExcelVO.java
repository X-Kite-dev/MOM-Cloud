package com.kitex.mom.common.message.vo;

import com.alibaba.excel.annotation.ExcelIgnore;
import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import com.kitex.common.excel.annotation.ExcelLine;
import lombok.Data;

import java.io.Serializable;

/**
 * 消息推送配置excel 对应的实体
 *
 * @author kitex
 * @date 2025-01-14 15:45:12
 */
@Data
@ColumnWidth(30)
public class MessageConfigExcelVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 导入时候回显行号
     */
    @ExcelLine
    @ExcelIgnore
    private Long lineNum;

    /**
     * 名称
     */
    @ExcelProperty("名称")
    private String name;

    /**
     * 编码
     */
    @ExcelProperty("编码")
    private String code;

    /**
     * 类型
     */
    @ExcelProperty("类型")
    private String type;

    /**
     * 应用ID
     */
    @ExcelProperty("应用ID")
    private String appId;

    /**
     * 应用密钥
     */
    @ExcelProperty("应用密钥")
    private String appSecret;

}
