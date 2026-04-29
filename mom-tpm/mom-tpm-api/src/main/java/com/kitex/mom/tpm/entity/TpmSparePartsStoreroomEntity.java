package com.kitex.mom.tpm.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 备件库房表
 *
 * @author kitex
 * @date 2025-02-11 16:52:52
 */
@Data
@TenantTable
@TableName("tpm_spare_parts_storeroom")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "备件库房表")
public class TpmSparePartsStoreroomEntity extends BaseEntity<TpmSparePartsStoreroomEntity> {


    /**
     * id
     */
    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "id")
    private Long id;

    /**
     * 库房编码
     */
    @Schema(description = "库房编码")
    private String code;

    /**
     * 库房名称
     */
    @Schema(description = "库房名称")
    private String name;

    /**
     * 负责人编码
     */
    @Schema(description = "负责人编码")
    private String picCode;

    /**
     * 负责人名称
     */
    @Schema(description = "负责人名称")
    private String picName;

    /**
     * 负责人手机号
     */
    @Schema(description = "负责人手机号")
    private String picPhone;

    /**
     * 库房地址
     */
    @Schema(description = "库房地址")
    private String address;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}