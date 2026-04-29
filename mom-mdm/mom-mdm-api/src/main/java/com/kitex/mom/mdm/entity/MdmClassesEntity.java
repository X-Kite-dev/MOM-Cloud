package com.kitex.mom.mdm.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalTime;

/**
 * 班次
 *
 * @author kitex
 * @date 2024-11-01 17:00:25
 */
@Data
@TenantTable
@TableName("mdm_classes")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "班次")
public class MdmClassesEntity extends BaseEntity<MdmClassesEntity> {

    /**
     * 班次编码
     */
    @Schema(description = "班次编码")
    private String shiftCode;

    /**
     * 班次名称
     */
    @Schema(description = "班次名称")
    private String shiftName;

    /**
     * 开始时间
     */
    @Schema(description = "开始时间")
    private LocalTime startTime;

    /**
     * 结束时间
     */
    @Schema(description = "结束时间")
    private LocalTime endTime;

    /**
     * 状态 (true=启用, false=禁用)
     */
    @Schema(description = "状态 (true=启用, false=禁用)")
    private Boolean status;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

}
