package com.kitex.mom.mes.dto;

import lombok.Data;

/**
 * 条码信息实体类
 */
@Data
public class BarcodeInfoDTO {
    /**
     * 物料数量
     */
    private String matNum;

    /**
     * 物料编码
     */
    private String matCode;

    /**
     * 物料名称
     */
    private String matName;

    /**
     * 物料批次
     */
    private String matBatch;

    /**
     * 供应商编码
     */
    private String supplierCode;

    /**
     * 供应商名称
     */
    private String supplierName;
} 