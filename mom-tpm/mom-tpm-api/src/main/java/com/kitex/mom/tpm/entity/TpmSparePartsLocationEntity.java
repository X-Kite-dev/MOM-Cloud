package com.kitex.mom.tpm.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 备件库位表
 *
 * @author kitex
 * @date 2025-02-11 16:46:35
 */
@Data
@TenantTable
@TableName("tpm_spare_parts_location")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "备件库位表")
public class TpmSparePartsLocationEntity extends BaseEntity<TpmSparePartsLocationEntity> {


    /**
     * id
     */
    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "id")
    private Long id;

    /**
     * 库位编码
     */
    @Schema(description = "库位编码")
    private String code;

    /**
     * 库位名称
     */
    @Schema(description = "库位名称")
    private String name;

    /**
     * 所属库房编码
     */
    @Schema(description = "所属库房编码")
    private String storeroomCode;

    /**
     * 所属库房名称
     */
    @TableField(exist = false)
    @Schema(description = "所属库房名称")
    private String storeroomName;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}