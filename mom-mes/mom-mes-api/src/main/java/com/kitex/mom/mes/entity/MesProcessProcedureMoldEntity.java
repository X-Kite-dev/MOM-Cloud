package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * <p>
 * 工艺路线-工序-模具表 实体类
 * </p>
 *
 * @author kitex
 * @date 2025-04-11 10:16:07
 */
@Data
@TenantTable
@TableName("mes_process_procedure_mold")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "工艺路线-工序-模具表")
public class MesProcessProcedureMoldEntity extends BaseEntity<MesProcessProcedureMoldEntity> {

    /**
     * 工艺路线编码
     */
    @Schema(description = "工艺路线编码")
    private String routeCode;

    /**
     * 工序编码
     */
    @Schema(description = "工序编码")
    private String processCode;

    /**
     * 模具编码
     */
    @Schema(description = "模具编码")
    private String moldCode;

    /**
     * 模具名称
     */
    @Schema(description = "模具名称")
    private String moldName;

    /**
     * 所属组织
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
