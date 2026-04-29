package com.kitex.mom.mes.entity;

import com.alibaba.excel.annotation.ExcelIgnore;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.github.yulichang.annotation.EntityMapping;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;

/**
 * <p>
 * 不合格锁定记录 实体类
 * </p>
 *
 * @author kitex
 * @date 2025-02-21 10:44:33
 */
@Data
@TenantTable
@TableName("mes_warehouse_unqualified_lock")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "不合格锁定记录")
public class MesWarehouseUnqualifiedLockEntity extends BaseEntity<MesWarehouseUnqualifiedLockEntity> {

    /**
     * 物料编码
     */
    @NotBlank(message = "物料编码为空")
    @Schema(description = "物料编码")
    private String matCode;

    /**
     * 物料名称
     */
    @Schema(description = "物料名称")
    private String matName;

    /**
     * 规格型号
     */
    @Schema(description = "规格型号")
    private String matSpec;

    /**
     * 批次号
     */
    @Schema(description = "批次号")
    private String matBatch;

    /**
     * 单位
     */
    @Schema(description = "单位")
    private String matUnit;

    /**
     * 物料条码
     */
    @Schema(description = "物料条码")
    private String matBarcode;

    /**
     * 物料条码图片
     */
    @Schema(description = "物料条码图片")
    private String matBarcodeImage;

    /**
     * 供应商编码
     */
    @Schema(description = "供应商编码")
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
     * 容器编码
     */
    @Schema(description = "容器编码")
    private String containerCode;

    /**
     * 容器名称
     */
    @Schema(description = "容器名称")
    private String containerName;

    /**
     * 库位编码
     */
    @Schema(description = "库位编码")
    private String siteCode;

    /**
     * 库位名称
     */
    @Schema(description = "库位名称")
    private String siteName;

    /**
     * 最近一次锁定来源（质量、生产）
     * WarehouseLockResourceEnum  MES QMS
     */
    @NotBlank(message = "锁定来源为空")
    @Schema(description = "最近一次锁定来源")
    private String lockResource;

    /**
     * 最近一次锁定时间
     */
    @Schema(description = "最近一次锁定时间")
    private LocalDateTime lockTime;

    /**
     * 最近一次锁定人员
     */
    @Schema(description = "最近一次锁定人员")
    private String lockPersonCode;

    /**
     * 最近一次锁定人员姓名
     */
    @Schema(description = "最近一次锁定人员姓名")
    private String lockPersonName;

    /**
     * 解锁人员
     */
    @Schema(description = "解锁人员")
    private String unlockPersonCode;

    /**
     * 解锁人员姓名
     */
    @Schema(description = "解锁人员姓名")
    private String unlockPersonName;

    /**
     * 解锁时间
     */
    @Schema(description = "解锁时间")
    private LocalDateTime unlockTime;

    /**
     * 锁定明细
     */
    @ExcelIgnore
    @TableField(exist = false)
    @EntityMapping(thisField = "id", joinField = "lockId")
    private List<MesWarehouseLockDetailEntity> mesWarehouseLockDetailList;

    /**
     * 状态
     */
    @TableField(exist = false)
    private String status;

    /**
     * 所属组织
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
