package com.kitex.mom.mes.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class MesWipTransferCommand {
    private String requestId;

    /** 可选工序目标；历史命令空值不得进入原JSON。 */
    private MesWipDispatchTargetDTO.Selection dispatchTarget;

    private String operationType;
    private Long transferId;
    private Long sourceStockId;
    private BigDecimal quantity;
    private BigDecimal expectedSourceQuantity;
    private Long expectedRevision;
    private String fromLocationCode;
    private String toLocationCode;
    private String scannedLocationCode;
    private String observedBarcode;
    private LocalDateTime dueTime;
    private String reason;

    /** 明确确认实物身份、数量与原质量条件未改变，异常件保持在途待处置。 */
    private Boolean conditionConfirmed;

    private String exceptionType;
    private String segregationEvidence;

    /** 仅确认已实际收到且完成现场隔离标识，不代表质量合格。 */
    private Boolean segregationConfirmed;
}
