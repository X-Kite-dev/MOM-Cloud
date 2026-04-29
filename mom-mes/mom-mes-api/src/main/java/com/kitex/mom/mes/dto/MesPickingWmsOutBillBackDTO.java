package com.kitex.mom.mes.dto;

import com.kitex.mom.mes.entity.MesWarehouseMaterialInBillEntity;
import lombok.Data;

import java.util.List;

/**
 * 领料出库单完成后返回结果
 *
 * @author kitex
 * @date 2025/2/28 16:05
 */
@Data
public class MesPickingWmsOutBillBackDTO {
    /**
     * 生产领料单id
     */
    private Long id;
    /**
     * 物料编码集合
     */
    private List<String> matCodes;
    /**
     * 出库单物料明细集合
     */
    private List<MesWarehouseMaterialInBillEntity> matDetails;
}
