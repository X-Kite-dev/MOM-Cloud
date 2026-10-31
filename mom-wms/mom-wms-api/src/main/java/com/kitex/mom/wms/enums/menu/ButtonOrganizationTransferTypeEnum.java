package com.kitex.mom.wms.enums.menu;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 本仓收发职责分别授权；维护组织策略不自动获得实物办理权限。
 */
@Getter
@AllArgsConstructor
public enum ButtonOrganizationTransferTypeEnum {
	VIEW("wms_orgTransfer_view", "查看", 1),
	ADD("wms_orgTransfer_add", "申请", 2),
	APPROVE("wms_orgTransfer_approve", "接收方确认", 3),
	SHIP("wms_orgTransfer_ship", "发出", 4),
	RECEIVE("wms_orgTransfer_receive", "接收及退回申请", 5),
	RETURN("wms_orgTransfer_return", "实物退回", 6),
	EXCEPTION("wms_orgTransfer_exception", "差异申报及复核", 7),
	CANCEL("wms_orgTransfer_cancel", "发出前作废", 8),
	CONFIG("wms_orgTransfer_config", "组织设置", 9);
	private final String code;
	private final String name;
	private final Integer sort;
}
