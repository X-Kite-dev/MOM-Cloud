package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * <p>
 * 箱码管理 实体类
 * </p>
 *
 * @author kitex
 * @since 2025-03-25
 */
@EqualsAndHashCode(callSuper = true)
@Data
@TenantTable
@Accessors(chain = true)
@Schema(description = "箱码管理")
@TableName("mes_wrap")
public class MesWrapEntity extends BaseEntity<MesWrapEntity> {

    /**
     * 箱码
     */
    @Schema(description = "箱码")
    private String wrapCode;

    /**
     * 规则编码
     */
    @Schema(description = "规则编码")
    private String ruleCode;

    /**
     * 箱码容量
     */
    @Schema(description = "箱码容量")
    private Integer wrapCapacity;

    /**
     * 工单编码
     */
    @Schema(description = "工单编码")
    private String taskCode;

    /**
     * 工序任务编号
     */
    @Schema(description = "工序任务编号")
    private String procedureTaskCode;

    /**
     * 派工单号
     */
    @Schema(description = "派工单号")
    private String arrangeCode;

    /**
     * 成品PRODUCT 半成品HALF
     */
    @Schema(description = "成品PRODUCT 半成品HALF")
    private String wrapType;

    /**
     * 所属组织
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

    /**
     * 任务类型
     */
    @TableField(exist = false)
    private String taskType;

}

