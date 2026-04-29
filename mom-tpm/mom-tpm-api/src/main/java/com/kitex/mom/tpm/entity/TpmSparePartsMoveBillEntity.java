package com.kitex.mom.tpm.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.kitex.common.core.entity.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 备件移库记录表
 *
 * @author kitex
 * @date 2025-02-11 16:48:31
 */
@Data
@TableName("tpm_spare_parts_move_bill")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "备件移库记录表")
public class TpmSparePartsMoveBillEntity extends BaseEntity<TpmSparePartsMoveBillEntity> {


    /**
     * id
     */
    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "id")
    private Long id;

    /**
     * 备件编码
     */
    @Schema(description = "备件编码")
    private String sparePartsCode;

    /**
     * 备件名称
     */
    @Schema(description = "备件名称")
    private String sparePartsName;

    /**
     * 库房编码
     */
    @Schema(description = "库房编码")
    private String storeroomCode;

    /**
     * 库房名称
     */
    @Schema(description = "库房名称")
    private String storeroomName;

    /**
     * 旧库位编码
     */
    @Schema(description = "旧库位编码")
    private String oldLocationCode;

    /**
     * 旧库位名称
     */
    @Schema(description = "旧库位名称")
    private String oldLocationName;

    /**
     * 新库位编码
     */
    @Schema(description = "新库位编码")
    private String newLocationCode;

    /**
     * 新库位名称
     */
    @Schema(description = "新库位名称")
    private String newLocationName;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}