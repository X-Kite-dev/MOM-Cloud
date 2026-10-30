package com.kitex.mom.wms.dto;

import com.kitex.mom.wms.entity.WmsInBillDetailEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "提交盘点差异审批；选定物料属性在审核后不可更改")
public class WmsInventoryAdjustmentApprovalSubmit {
    private String requestId;
    private Long planDetailId;
    private Double expectedCurrentNumber;
    private Double expectedInventoryNum;
    private WmsInBillDetailEntity detail;
    private String reason;
}
