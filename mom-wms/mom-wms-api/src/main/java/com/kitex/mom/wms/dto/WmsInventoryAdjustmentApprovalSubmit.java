package com.kitex.mom.wms.dto;

import com.kitex.mom.wms.entity.WmsInBillDetailEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "提交盘点差异审批；选定物料属性在审核后不可更改")
public class WmsInventoryAdjustmentApprovalSubmit {
	private String requestId;
	private Long planDetailId;
	private Double expectedCurrentNumber;
	private Double expectedInventoryNum;
	private WmsInBillDetailEntity detail;
	private String reason;
	@Schema(description = "盘盈新增数量已核对为本企业自有；历史申请不自动补写确认")
	private Boolean ownershipConfirmed;
	@Schema(description = "可选指定盘亏实物；来源和分配在审批后不可替换")
	private List<WmsInventoryLossStockRequest> lossStocks;
}
