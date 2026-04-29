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
 * 物料预警表
 *
 * @author kitex
 * @date 2024-12-23 14:58:53
 */
@Data
@TenantTable
@TableName("wms_material_warning")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "物料预警表")
public class WmsMaterialWarningEntity extends BaseEntity<WmsMaterialWarningEntity> {

    /**
     * 预警号
     */
    @Schema(description = "预警号")
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
     * 规格型号
     */
    @Schema(description = "规格型号")
    private String matSpec;

    /**
     * 物料批次
     */
    @Schema(description = "物料批次")
    private String matBatch;

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
     * 库位编码
     */
    @Schema(description = "库位编码")
    private String siteCode;

    /**
     * 库存名称
     */
    @Schema(description = "库存名称")
    private String siteName;

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
     * 预警类型, 0 过期预警 1 呆滞预警
     */
    @Schema(description = "预警类型, 0 过期预警 1 呆滞预警")
    private String type;

    /**
     * 预警天数
     */
    @Schema(description = "预警天数")
    private Integer warningDays;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}