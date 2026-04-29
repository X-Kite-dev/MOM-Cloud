package com.kitex.mom.qms.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import com.kitex.mom.qms.handler.JacksonRawTypeHandler;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.util.List;

/**
 * 抽样策略
 *
 * @author kitex
 * @date 2022/11/29
 */
@EqualsAndHashCode(callSuper = true)
@Data
@TenantTable
@Accessors(chain = true)
@TableName(value = "qms_quality_sampling_strategies", autoResultMap = true)
@Schema(description = "抽样策略")
public class QmsQualitySamplingStrategiesEntity extends BaseEntity<QmsQualitySamplingStrategiesEntity> {

    @Schema(description = "AQL")
    private Double aql;

    @Schema(description = "检验水平")
    private String inspectionLevel;

    @Schema(description = "抽样范围")
    @TableField(typeHandler = JacksonRawTypeHandler.class)
    private List<Frequency> frequency;

    @Schema(description = "备注")
    private String remark;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

}
