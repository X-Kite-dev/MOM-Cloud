package com.kitex.mom.wms.dto;

import com.kitex.mom.wms.entity.WmsInBillDetailEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "在原盘点范围准备盘盈候选属性，不提交审批或调整库存")
public class WmsInventoryGainPreparationCommand {
	private Long planDetailId;
	private Double expectedCurrentNumber;
	private Double expectedInventoryNum;
	private WmsInBillDetailEntity detail;
}
