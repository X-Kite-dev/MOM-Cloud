package com.kitex.mom.wms.dto.app;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 实时库存DTO
 *
 * @author kitex
 * @date 2025-01-08 12:41:58
 */
@Data
@Schema(description = "实时库存DTO")
public class WmsLiveStockMoveDTO {

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
     * 包装规格
     */
    @Schema(description = "包装规格")
    private String matPackSpec;

    /**
     * 包装单位
     */
    @Schema(description = "包装单位")
    private String operateUnit;

    /**
     * 操作数量（包装数量）
     */
    @Schema(description = "操作数量（包装数量）")
    private Double operateNum;

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
     * 旧仓库编码
     */
    @Schema(description = "旧仓库编码")
    private String oldWarehouseCode;

    /**
     * 旧仓库名称
     */
    @Schema(description = "旧仓库名称")
    private String oldWarehouseName;

    /**
     * 新仓库编码
     */
    @Schema(description = "新仓库编码")
    private String newWarehouseCode;

    /**
     * 新仓库名称
     */
    @Schema(description = "新仓库名称")
    private String newWarehouseName;

    /**
     * 新库位编码
     */
    @Schema(description = "新库位编码")
    private String newSiteCode;

    /**
     * 新库存名称
     */
    @Schema(description = "新库位名称")
    private String newSiteName;

    /**
     * 旧库位编码
     */
    @Schema(description = "旧库位编码")
    private String oldSiteCode;

    /**
     * 旧库存名称
     */
    @Schema(description = "旧库存名称")
    private String oldSiteName;

    /**
     * 旧容器编码
     */
    @Schema(description = "旧容器编码")
    private String oldContainerCode;

    /**
     * 新容器编码
     */
    @Schema(description = "新容器编码")
    private String newContainerCode;

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
     * 有效期
     */
    @Schema(description = "有效期")
    private Integer matValidPeriod;

    /**
     * 有效截至日期
     */
    @Schema(description = "有效截至日期")
    private LocalDate matValidEndDate;

    /**
     * 质检结果
     */
    @Schema(description = "质检结果")
    private String qualityResult;

    /**
     * 状态 是否被锁定;0否 1是
     */
    @Schema(description = "状态 是否被锁定;0否 1是")
    private Integer status;

    /**
     * 扩展字段
     */
    @Schema(description = "扩展字段")
    private String expansion;

    /**
     * 有效期检测状态
     */
    @Schema(description = "有效期检测状态")
    private Boolean expirationStatus;

    /**
     * 呆滞物料检测状态
     */
    @Schema(description = "呆滞物料检测状态")
    private Boolean slowMaterialStatus;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    private String factoryCode;
}