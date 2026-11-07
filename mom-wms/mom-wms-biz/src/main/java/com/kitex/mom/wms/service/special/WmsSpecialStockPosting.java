package com.kitex.mom.wms.service.special;

import com.kitex.mom.wms.dto.WmsSpecialMovementDTO.*;
import com.kitex.mom.wms.service.special.WmsSpecialAccess.Scope;

import java.util.List;

/**
 * 专用流程只通过此端口调用原收发引擎，返回实际记账凭据。
 */
public interface WmsSpecialStockPosting {
	record Posted(String billId, String billNo, Direction direction, List<Flow> flows) {
	}

	Posted post(Scope scope, OrderDocument order, Command command, List<Flow> previous);

	void verify(Scope scope, Receipt receipt);
}
