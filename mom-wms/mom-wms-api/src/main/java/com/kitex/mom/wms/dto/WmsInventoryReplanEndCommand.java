package com.kitex.mom.wms.dto;

import lombok.Data;

@Data
public class WmsInventoryReplanEndCommand {
	private WmsInventoryReplanCommand original;
	private String reason;
}
