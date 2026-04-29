package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 添加锁定条件
 *
 * @author kitex
 * @date 2025-02-21 10:44:33
 */
@Data
@Schema(description = "添加锁定条件")
public class MesWarehouseUnqualifiedLockAddLockDTO {

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
     * 容器编码
     */
    @Schema(description = "容器编码")
    private String containerCode;

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
     * 锁定来源
     */
    @Schema(description = "锁定来源")
    private String lockResource;

}