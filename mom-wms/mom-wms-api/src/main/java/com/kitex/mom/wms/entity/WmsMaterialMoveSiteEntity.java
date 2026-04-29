package com.kitex.mom.wms.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 库内移动
 *
 * @author kitex
 * @date 2024-12-30 14:13:22
 */
@Data
@TenantTable
@TableName("wms_material_move_site")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "库内移动")
public class WmsMaterialMoveSiteEntity extends BaseEntity<WmsMaterialMoveSiteEntity> {

    /**
     * 库内移动号
     */
    @Schema(description = "库内移动号")
    private String billNo;

    /**
     * 物料编码
     */
    @Schema(description = "物料编码")
    private String matCode;

    /**
     * 物料名称
     */
    @Schema(description = "物料名称")
    private String matName;

    /**
     * 物料分组
     */
    @Schema(description = "物料分组")
    private String matGroup;

    /**
     * 物料分组名称
     */
    @Schema(description = "物料分组名称")
    private String matGroupName;

    /**
     * 物料条码
     */
    @Schema(description = "物料条码")
    private String matBarcode;

    /**
     * 物料批次
     */
    @Schema(description = "物料批次")
    private String matBatch;

    /**
     * 包装规格
     */
    @Schema(description = "包装规格")
    private String matPackSpec;

    /**
     * 供应商
     */
    @Schema(description = "供应商")
    private String supplierCode;

    /**
     * 供应商名称
     */
    @Schema(description = "供应商名称")
    private String supplierName;

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
     * 新库位编码
     */
    @Schema(description = "新库位编码")
    private String newSiteCode;

    /**
     * 新库位名称
     */
    @Schema(description = "新库位名称")
    private String newSiteName;

    /**
     * 旧库位编码
     */
    @Schema(description = "旧库位编码")
    private String oldSiteCode;

    /**
     * 旧库位名称
     */
    @Schema(description = "旧库位名称")
    private String oldSiteName;

    /**
     * 容器编码
     */
    @Schema(description = "容器编码")
    private String containerCode;

    /**
     * 入库时间
     */
    @Schema(description = "入库时间")
    private LocalDateTime inTime;

    /**
     * 数量
     */
    @Schema(description = "数量")
    private Double matNum;

    /**
     * 单位
     */
    @Schema(description = "单位")
    private String matUnit;

    /**
     * 生产日期
     */
    @Schema(description = "生产日期")
    private LocalDate matProduceDate;

    /**
     * 规格型号
     */
    @Schema(description = "规格型号")
    private String matSpec;

    /**
     * 有效期
     */
    @Schema(description = "有效期")
    private Long matValidPeriod;

    /**
     * 有效截至日期
     */
    @Schema(description = "有效截至日期")
    private LocalDate matValidEndDate;

    /**
     * 包装单位
     */
    @Schema(description = "包装单位")
    private String operateUnit;

    /**
     * 包装单位数量
     */
    @Schema(description = "包装单位数量")
    private Double formatNum;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}