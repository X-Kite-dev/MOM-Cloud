package com.kitex.mom.wms.dto;

import lombok.Data;

/**
 * 结束尚未登记的原请求，不撤销已有复盘。
 */
@Data
public class WmsInventoryRecountTerminationCommand {
	private WmsInventoryRecountCommand original;
	private String reason;
}
