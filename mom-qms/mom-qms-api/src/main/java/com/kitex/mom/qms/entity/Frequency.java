package com.kitex.mom.qms.entity;

import cn.afterturn.easypoi.excel.annotation.Excel;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 检测频次
 *
 * @author kitex
 * @date 2022/11/29
 */
@Data
@Accessors(chain = true)
@Schema(description = "检测频次")
public class Frequency {

    @Excel(name = "开始数量")
    @Schema(description = "开始范围")
    private Integer s;

    @Excel(name = "结束数量")
    @Schema(description = "结束范围")
    private Integer e;

    @Excel(name = "抽样数(0为全检)")
    @Schema(description = "抽多少个")
    private Integer n;

    @Excel(name = "通过阈值数量(AC)")
    @Schema(description = "接收数量临界")
    private Integer ac;

    @Excel(name = "拒收阈值数量(RE)")
    @Schema(description = "拒收数量临界")
    private Integer re;

}
