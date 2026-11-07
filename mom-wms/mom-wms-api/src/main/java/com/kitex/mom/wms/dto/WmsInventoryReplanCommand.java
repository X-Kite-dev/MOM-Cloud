package com.kitex.mom.wms.dto;

import lombok.Data;

/**
 * 新盘点只接受原计划和意图，不接受客户端账面或实盘。
 */
@Data
public class WmsInventoryReplanCommand {
	private Long sourcePlanId;
	private String requestId;
	private String sourceVersion;
	private String newPlanNo;
	private String reason;
	private String stockFreezeMode;
}
