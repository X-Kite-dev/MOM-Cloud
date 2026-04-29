package com.kitex.mom.common.message.vo;

import com.alibaba.excel.annotation.ExcelIgnore;
import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import com.kitex.common.excel.annotation.ExcelLine;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 消息推送记录excel 对应的实体
 *
 * @author kitex
 * @date 2025-01-14 10:24:15
 */
@Data
@ColumnWidth(30)
public class MessageLogExcelVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 导入时候回显行号
     */
    @ExcelLine
    @ExcelIgnore
    private Long lineNum;

    /**
     * 推送第三方类型
     */
    @ExcelProperty("推送第三方类型")
    private String type;

    /**
     * 推送模块
     */
    @ExcelProperty("推送模块")
    private String moduleCode;

    /**
     * 推送人
     */
    @ExcelProperty("推送人")
    private String user;

    /**
     * 推送时间
     */
    @ExcelProperty("推送时间")
    private LocalDateTime time;

    /**
     * 推送内容
     */
    @ExcelProperty("推送内容")
    private String content;

    /**
     * 结果
     */
    @ExcelProperty("结果")
    private String result;

}
