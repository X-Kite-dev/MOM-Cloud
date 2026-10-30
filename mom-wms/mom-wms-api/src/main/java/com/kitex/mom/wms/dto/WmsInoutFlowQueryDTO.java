package com.kitex.mom.wms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 出入库流水查询参数
 *
 * @author kitex
 * @date 2024-12-18 18:48:20
 */
@Data
@Schema(description = "出入库流水查询参数")
public class WmsInoutFlowQueryDTO {

    /**
     * 单据号
     */
    @Schema(description = "单据号")
    private String billNo;

    /**
     * 物料编码
     */
    @Schema(description = "物料编码")
    private String matCode;

    @Schema(description = "物料批次，精确匹配")
    private String matBatch;

    @Schema(description = "条码或序列号，精确匹配")
    private String matBarcode;

    @Schema(description = "首次入库单明细ID")
    private Long sourceInBillDetailId;

    @Schema(description = "实际扣减或形成的库存行ID")
    private Long sourceStockId;

    @Schema(description = "库存货权")
    private String stockOwnerType;

    @Schema(description = "库存归属方编码")
    private String stockOwnerCode;

    @Schema(description = "首次入库来源业务单号")
    private String sourceBusinessNo;

    /**
     * 物料名称
     */
    @Schema(description = "物料名称")
    private String matName;

    /**
     * 仓库编号
     */
    @Schema(description = "仓库编号")
    private List<String> warehouseCode;

    /**
     * 库位编号
     */
    @Schema(description = "库位编号")
    private String siteCode;

    /**
     * 操作类型;in入库、out出库
     */
    @Schema(description = "操作类型;in入库、out出库")
    private String operateType;

    /**
     * 业务类型
     */
    @Schema(description = "业务类型")
    private String billType;

    /**
     * 操作时间
     */
    @Schema(description = "操作时间")
    private List<LocalDateTime> createTime;

}
