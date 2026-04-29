package com.kitex.mom.pms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 价格库DTO
 *
 * @author kitex
 * @date 2025-03-24 17:39:49
 */
@Data
@Schema(description = "价格库DTO")
public class PmsPriceDTO {

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
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    private String factoryCode;

    /**
     * 物料列表
     */
    @Schema(description = "物料列表")
    private List<PmsPriceMatDTO> matList;
}