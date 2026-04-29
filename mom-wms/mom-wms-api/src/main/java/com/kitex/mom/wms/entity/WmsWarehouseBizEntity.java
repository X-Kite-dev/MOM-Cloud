package com.kitex.mom.wms.entity;

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
 * 仓库业务配置
 *
 * @author kitex
 * @date 2025-01-13 17:48:14
 */
@Data
@TenantTable
@Accessors(chain = true)
@TableName("wms_warehouse_biz")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "仓库业务配置")
public class WmsWarehouseBizEntity extends BaseEntity<WmsWarehouseBizEntity> {

    /**
     * 仓库编码
     */
    @Schema(description = "仓库编码")
    private String warehouseCode;

    /**
     * 业务功能
     */
    @Schema(description = "业务功能")
    private String bizType;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}