package com.kitex.mom.wms.dto;

import com.kitex.mom.wms.entity.WmsInBillDetailEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "按盘点明细统一办理损益；数量及物料范围始终以服务端原记录为准")
public class WmsInventoryAdjustmentCommand {
	private Long planDetailId;
	private Double expectedCurrentNumber;
	private Double expectedInventoryNum;
	@Schema(description = "所选批次、供应商、容器及盘盈物料属性；不能更改原物料、库位或差异数量")
	private WmsInBillDetailEntity detail;
}
