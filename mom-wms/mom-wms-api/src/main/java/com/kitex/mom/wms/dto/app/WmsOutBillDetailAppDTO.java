package com.kitex.mom.wms.dto.app;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * app出库参数
 *
 * @author kitex
 * @date 2024-12-30 16:10:32
 */
@Data
@Accessors(chain = true)
@Schema(description = "app出库参数")
public class WmsOutBillDetailAppDTO {

    /**
     * 业务冻结的精确实时库存主键。
     * 采购退供等受控场景必须携带，避免同物料多批次或多库位时误选库存。
     */
    @Schema(description = "来源实时库存主键")
    private Long sourceStockId;

    /**
     * 通知单号
     */
    @NotNull(message = "通知单号不能为空")
    @Schema(description = "通知单号")
    private Long noticeId;

    /**
     * 出库单号
     */
    @Schema(description = "出库单号")
    private String billNo;

    /**
     * 物料编码
     */
    @NotNull(message = "物料编码不能为空")
    @Schema(description = "物料编码")
    private String matCode;

    /**
     * 物料名称
     */
    @Schema(description = "物料名称")
    private String matName;

    /**
     * 物料条码
     */
    @Schema(description = "物料条码")
    private String matBarcode;

    /**
     * 物料批次
     */
    @NotNull(message = "物料批次不能为空")
    @Schema(description = "物料批次")
    private String matBatch;

    /**
     * 操作数量
     */
    @Schema(description = "操作数量")
    private Double operateNum;

    /**
     * 操作单位
     */
    @Schema(description = "操作单位")
    private String operateUnit;

    /**
     * 出库数量
     */
    @NotNull(message = "出库数量不能为空")
    @Schema(description = "出库数量")
    private Double matNum;

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
     * 库位编码
     */
    @NotNull(message = "库位编码不能为空")
    @Schema(description = "库位编码")
    private String siteCode;

    /**
     * 库位名称
     */
    @Schema(description = "库位名称")
    private String siteName;

    /**
     * 容器编码
     */
    @Schema(description = "容器编码")
    private String containerCode;

}
