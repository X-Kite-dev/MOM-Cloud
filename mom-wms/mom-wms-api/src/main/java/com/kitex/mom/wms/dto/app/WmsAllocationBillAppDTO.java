package com.kitex.mom.wms.dto.app;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 调拨单app保存实体
 *
 * @author kitex
 * @date 2024-12-18 14:09:03
 */
@Data
public class WmsAllocationBillAppDTO {

    /**
     * 调拨单号
     */
    @Schema(description = "调拨单号")
    private String billNo;

    /**
     * 旧仓库编码
     */
    @Schema(description = "旧仓库编码")
    @NotNull(message = "旧仓库不能为空")
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
    @NotNull(message = "新仓库不能为空")
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
    @NotNull(message = "新库位不能为空")
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
    @NotNull(message = "旧库位不能为空")
    private String oldSiteCode;

    /**
     * 旧库存名称
     */
    @Schema(description = "旧库存名称")
    private String oldSiteName;

    /**
     * 物料编码
     */
    @Schema(description = "物料编码")
    @NotNull(message = "物料不能为空")
    private String matCode;

    /**
     * 物料名称
     */
    @Schema(description = "物料名称")
    private String matName;

    /**
     * 物料批次
     */
    @Schema(description = "物料批次")
    @NotNull(message = "物料批次不能为空")
    private String matBatch;

    /**
     * 供应商编码
     */
    @Schema(description = "供应商编码")
    @NotNull(message = "供应商次不能为空")
    private String supplierCode;

    /**
     * 供应商名称
     */
    @Schema(description = "供应商名称")
    private String supplierName;

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
     * 数量
     */
    @Schema(description = "数量")
    @NotNull(message = "数量不能为空")
    private Double operateNum;

    /**
     * 单位
     */
    @Schema(description = "单位")
    private String matUnit;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    private String factoryCode;

}