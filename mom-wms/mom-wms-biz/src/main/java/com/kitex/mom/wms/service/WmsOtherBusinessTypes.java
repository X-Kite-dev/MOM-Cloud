package com.kitex.mom.wms.service;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.wms.enums.OtherInBillTypeEnum;
import com.kitex.mom.wms.enums.OtherOutBillTypeEnum;

/**
 * 其他收发正文入口的类型边界；保留原类型交由原处理器核对来源、审批及库存。
 */
public final class WmsOtherBusinessTypes {
	private WmsOtherBusinessTypes() {
	}

	public static String requireInbound(String type) {
		if ("other_in".equals(type) || OtherInBillTypeEnum.isValid(type)) {
			return type;
		}
		throw new CheckedException("请选择其他入库支持的业务类型，采购、成品等业务请从对应入口办理");
	}

	public static String requireOutbound(String type) {
		if ("other_out".equals(type) || OtherOutBillTypeEnum.isValid(type)) {
			return type;
		}
		throw new CheckedException("请选择其他出库支持的业务类型，生产领料、销售等业务请从对应入口办理");
	}
}
