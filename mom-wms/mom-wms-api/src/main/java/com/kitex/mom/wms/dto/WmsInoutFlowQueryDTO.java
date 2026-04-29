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