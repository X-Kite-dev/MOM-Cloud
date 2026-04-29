package com.kitex.mom.tpm.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 备件库存表
 *
 * @author kitex
 * @date 2025-02-11 16:51:06
 */
@Data
@TenantTable
@TableName("tpm_spare_parts_stock")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "备件库存表")
public class TpmSparePartsStockEntity extends BaseEntity<TpmSparePartsStockEntity> {


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

    @TableField(exist = false)
    @Schema(description = "备件名称")
    private String sparePartsName;

    /**
     * 库房编码
     */
    @Schema(description = "库房编码")
    private String storeroomCode;

    @TableField(exist = false)
    @Schema(description = "库房名称")
    private String storeroomName;

    /**
     * 库位编码
     */
    @Schema(description = "库位编码")
    private String locationCode;

    @TableField(exist = false)
    @Schema(description = "库位名称")
    private String locationName;

    /**
     * 数量
     */
    @Schema(description = "数量")
    private BigDecimal inventoryNum;

    /**
     * 单位
     */
    @TableField(exist = false)
    @Schema(description = "单位")
    private String unit;

    /**
     * 规格
     */
    @TableField(exist = false)
    @Schema(description = "规格")
    private String spec;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;


    @TableField(exist = false)
    @Schema(description = "id集合")
    private Long[] ids;
}