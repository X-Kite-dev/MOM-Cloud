package com.kitex.mom.tpm.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 故障类型表
 *
 * @author kitex
 * @date 2025-02-06 15:25:03
 */
@Data
@TenantTable
@TableName("tpm_repair_fault_class")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "故障类型表")
public class TpmRepairFaultClassEntity extends BaseEntity<TpmRepairFaultClassEntity> {


    /**
     * id
     */
    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "id")
    private Long id;

    /**
     * 故障名称
     */
    @Schema(description = "故障名称")
    private String name;

    /**
     * 备注
     */
    @Schema(description = "备注")
    private String remark;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}