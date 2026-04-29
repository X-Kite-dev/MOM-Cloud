package com.kitex.mom.wms.vo;

import com.kitex.mom.wms.dto.WmsDetailDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 调拨单返回实体
 *
 * @author kitex
 * @date 2024-12-18 14:09:03
 */
@Data
public class WmsAllocationBillVO {

    /**
     * 调拨单id
     */
    @Schema(description = "调拨单id")
    private Long id;

    /**
     * 调拨单号
     */
    @Schema(description = "调拨单号")
    private String billNo;

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
     * 旧仓库类型
     */
    @Schema(description = "旧仓库类型")
    private String oldWarehouseType;

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
     * 新仓库类型
     */
    @Schema(description = "新仓库类型")
    private String newWarehouseType;

    /**
     * 备注
     */
    @Schema(description = "备注")
    private String remark;

    /**
     * 类型: product 成品 purchase 原辅 outsource 外协 research 研发
     */
    @Schema(description = "类型: product 成品 purchase 原辅 outsource 外协 research 研发")
    private String billType;

    /**
     * 组织id
     */
    @Schema(description = "组织id")
    private String factoryCode;

    /**
     * 物料列表
     */
    @Schema(description = "物料列表")
    private List<WmsDetailDTO> details;
}