package com.kitex.mom.qms.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import com.kitex.common.query.annotation.QueryColumn;
import com.kitex.common.query.enums.QueryRuleEnum;
import com.kitex.mom.qms.enums.InspectionTaskTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * 质检标准
 *
 * @author kitex
 * @date 2022/12/01
 */
@EqualsAndHashCode(callSuper = true)
@Data
@TenantTable
@Accessors(chain = true)
@TableName(value = "qms_quality_standard", autoResultMap = true)
@Schema(description = "质检标准")
public class QmsQualityStandardEntity extends BaseEntity<QmsQualityStandardEntity> {

    /**
     * @see InspectionTaskTypeEnum
     */
    @Schema(description = "质检类型")
    @QueryColumn(type = QueryRuleEnum.EQ)
    private String inspectionType;

    @Schema(description = "质检标准名称")
    private String standardName;

    @Schema(description = "质检物料")
    private String matCode;

    @Schema(description = "质检物料")
    private String matName;

    @Schema(description = "规格型号")
    private String matSpec;

    /**
     * @see com.kitex.mom.qms.enums.InspectionMethodEnum
     */
    @Schema(description = "质检方式")
    private String inspectionMethod;

    @Schema(description = "质检数量")
    private Integer num;

    @Schema(description = "状态")
    @QueryColumn(type = QueryRuleEnum.EQ)
    private Integer published;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
