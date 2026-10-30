package com.kitex.mom.wms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "盘点差异审批结论")
public class WmsInventoryAdjustmentApprovalReview {
    private Long approvalId;
    private String decision;
    private String comment;
}
