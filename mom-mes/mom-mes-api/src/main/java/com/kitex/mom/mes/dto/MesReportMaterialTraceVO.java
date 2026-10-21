package com.kitex.mom.mes.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

/**
 * 报工实际用料统一追溯视图，兼容人工投料消耗和倒冲库存流水。
 */
@Data
@Accessors(chain = true)
public class MesReportMaterialTraceVO {
    private String sourceType;
    private String requirementMatCode;
    private String matCode;
    private String matBatch;
    private String matBarcode;
    private BigDecimal requirementNum;
    private BigDecimal consumeNum;
    private Boolean substituteFlag;
    private BigDecimal substituteRatio;
    private String status;
}
