package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 实时库存查询参数
 *
 * @author kitex
 * @date 2024-12-20 17:15:47
 */
@Data
@Accessors(chain = true)
@Schema(description = "实时库存查询参数")
public class MesLiveStockQueryDTO {

    /**
     * 工单号
     */
    @Schema(description = "工单号")
    private String taskCode;

    /**
     * 工序编码
     */
    @Schema(description = "工序编码")
    private String routeCode;

    /**
     * 工序名称
     */
    @Schema(description = "工序名称")
    private String routeName;

    /**
     * 车间编码
     */
    @Schema(description = "车间编码")
    private String workshopCode;

    /**
     * 派工班次编码
     */
    @Schema(description = "派工班次编码")
    private String shiftCode;

    /**
     * 物料明细类型 原材料、半成品、在制品
     */
    @Schema(description = "物料明细类型 原材料、半成品、在制品")
    private String matDetailType;

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
     * 库位编码
     */
    @Schema(description = "库位编码")
    private List<String> siteCode;

    /**
     * 库位id
     */
    @Schema(description = "库位id")
    private Long siteId;

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
     * 入库时间
     */
    @Schema(description = "入库时间")
    private List<LocalDateTime> inTime;

    /**
     * 生产日期
     */
    @Schema(description = "生产日期")
    private List<LocalDate> matProduceDate;

    /**
     * 有效截至日期
     */
    @Schema(description = "有效截至日期")
    private List<LocalDate> matValidEndDate;

    private String factoryCode;

}