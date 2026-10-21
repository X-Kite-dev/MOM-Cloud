package com.kitex.mom.wms.vo;

import com.kitex.mom.wms.entity.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
public class WmsTransferVarianceCaseVO extends WmsTransferVarianceCaseEntity {
    private List<WmsTransferVarianceLineEntity> lines;
    private List<WmsTransferReceiptEntity> receipts;
    private List<WmsTransferReceiptLineEntity> receiptLines;
    private List<WmsTransferVarianceOperationEntity> operations;
}
