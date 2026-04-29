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
 * 容器码
 *
 * @author kitex
 * @date 2025-01-02 14:57:19
 */
@Data
@TenantTable
@Accessors(chain = true)
@TableName("wms_container_barcode")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "容器码")
public class WmsContainerBarcodeEntity extends BaseEntity<WmsContainerBarcodeEntity> {

    /**
     * 容器条码信息
     */
    @Schema(description = "容器条码信息")
    private String code;

    /**
     * 是否被占用
     */
    @Schema(description = "是否被占用")
    private Integer isUsed;

    /**
     * 容器类型
     */
    @Schema(description = "容器类型")
    private String typeCode;

    /**
     * 仓库编码
     */
    @Schema(description = "仓库编码")
    private String warehouseCode;

    /**
     * 仓库名称
     */
    @Schema(description = "仓库名称")
    private String warehouseName;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}