package com.kitex.mom.pms.dto;

import com.kitex.mom.pms.entity.PmsSupplierFileEntity;
import com.kitex.mom.pms.entity.PmsSupplierMatEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 供应商-供货DTO
 *
 * @author kitex
 * @date 2025-03-20 09:40:57
 */
@Data
@Schema(description = "供应商-供货DTO")
public class PmsSupplierDeliverDTO {

    /**
     * 供应商id
     */
    @Schema(description = "供应商id")
    private Long id;

    /**
     * 供应商-供货物料列表
     */
    @Schema(description = "供应商-供货物料列表")
    private List<PmsSupplierMatEntity> matList;

    /**
     * 供应商-供货附件列表
     */
    @Schema(description = "供应商-供货附件列表")
    private List<PmsSupplierFileEntity> fileList;
}