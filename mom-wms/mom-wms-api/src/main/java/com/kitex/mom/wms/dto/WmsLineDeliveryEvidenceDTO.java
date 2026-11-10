package com.kitex.mom.wms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

/**
 * MES签收前实时读取的WMS线边配送证据。
 */
@Data
@Accessors(chain = true)
public class WmsLineDeliveryEvidenceDTO {
    private Long taskId;
    private String taskNo;
    private String state;
    private Long outBillId;
    private Long outBillDetailId;
    private String factoryCode;

    /** 原配送任务保存的同厂目标工位编码，未派单时可为空。 */
    @Schema(description = "原配送任务的目标工位编码", accessMode = Schema.AccessMode.READ_ONLY)
    private String targetSiteCode;

    private String matCode;
    private BigDecimal plannedNum;
    private BigDecimal deliveredNum;
    private BigDecimal recoveredNum;
    private BigDecimal receivableNum;
    private String sourceFingerprint;
}
