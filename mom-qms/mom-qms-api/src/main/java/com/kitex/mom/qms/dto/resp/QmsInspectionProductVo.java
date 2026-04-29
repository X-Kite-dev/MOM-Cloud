package com.kitex.mom.qms.dto.resp;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.mom.qms.dto.inspection.InspectionItemResultRecord;
import com.kitex.mom.qms.enums.CheckTypeEnum;
import com.kitex.mom.qms.handler.JacksonRawTypeHandler;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@TableName(value = "QmsInspectionProductVo", autoResultMap = true)
public class QmsInspectionProductVo {

    private String matCode;


    @Schema(description = "检验项目名称")
    private String name;

    @Schema(description = "检验要求")
    private String demand;

    @Schema(description = "检测方法")
    private String method;

    @Schema(description = "检测位置")
    private String position;

    /**
     * @see CheckTypeEnum
     */
    @Schema(description = "点检类型")
    private String checkType;

    @Schema(description = "上限 点检类型为上限or范围时必填")
    private Double upperLimit;

    @Schema(description = "下限 点检类型为下限or范围时必填")
    private Double lowerLimit;

    @Schema(description = "总检测量")
    private Integer totalNum;

    @Schema(description = "已检测量")
    private Integer finishNum = 0;

    @Schema(description = "所需检测量")
    private Integer waitNum;

    @Schema(description = "不合格数量")
    private Integer unqualifiedNum = 0;

    @Schema(description = "当前AC值(允收数量)")
    private Integer ac;

    @Schema(description = "当前RE值(拒收数量)")
    private Integer re;

    @Schema(description = "合格状态")
    private Boolean qualifiedStatus;

    @Schema(description = "检测记录")
    @TableField(typeHandler = JacksonRawTypeHandler.class)
    private List<InspectionItemResultRecord> records = new ArrayList<>();


    @Schema(description = "数值 点检类型为上下限or范围时必填")
    private Double doubleValue;

    @Schema(description = "是否合格, 点检类型Bool时必填")
    private Boolean qualified;

}
