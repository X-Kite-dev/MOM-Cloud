package com.kitex.mom.qms.entity;

import cn.afterturn.easypoi.excel.annotation.Excel;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.mom.qms.enums.CheckTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * 质检项
 *
 * @author kitex
 * @date 2022/11/29
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName(value = "qms_quality_standard_item", autoResultMap = true)
@Schema(description = "质检标准项")
public class QmsQualityStandardItemEntity extends BaseEntity<QmsQualityStandardItemEntity> {

    @Schema(description = "质检标准id")
    private Long standardId;

    @Schema(description = "质检标准名称")
    private String standardName;

    @Excel(name = "质检项名称")
    @Schema(description = "质检项名称")
    private String name;

    @Schema(description = "质检要求")
    @Excel(name = "质检要求")
    private String demand;

    @Schema(description = "质检方法")
    @Excel(name = "质检方法")
    private String method;

    @Schema(description = "质检位置")
    @Excel(name = "质检位置")
    private String position;

    /**
     * @see com.kitex.mom.qms.enums.CheckClassifyEnum
     */
    @Schema(description = "质检分类")
    @Excel(name = "质检分类")
    private String checkClassify;

    @Schema(description = "抽样策略")
    private String samplingStrategiesId;

    @Schema(description = "抽样策略")
    private String samplingStrategies;

    /**
     * @see CheckTypeEnum
     */
    @Schema(description = "质检类型")
    @Excel(name = "质检类型")
    private String checkType;

    @Schema(description = "上限 点检类型为上限or范围时必填")
    @Excel(name = "上限")
    private Double upperLimit;

    @Schema(description = "下限 点检类型为下限or范围时必填")
    @Excel(name = "下限")
    private Double lowerLimit;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
