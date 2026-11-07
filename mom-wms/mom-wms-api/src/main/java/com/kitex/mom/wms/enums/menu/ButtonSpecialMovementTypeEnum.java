package com.kitex.mom.wms.enums.menu;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 各原仓独立授权，规则维护不自动获得审核或实物记账权限。
 */
@Getter
@AllArgsConstructor
public enum ButtonSpecialMovementTypeEnum {
	VIEW("wms_specialMovement_view", "查看", 1),
	ADD("wms_specialMovement_add", "申请及未执行取消", 2),
	REVIEW("wms_specialMovement_review", "审核", 3),
	EXECUTE("wms_specialMovement_execute", "实收实发", 4),
	RETURN("wms_specialMovement_return", "原件退回", 5),
	CLOSE("wms_specialMovement_close", "对账关闭", 6),
	CONFIG("wms_specialMovement_config", "规则配置", 7);
	private final String code;
	private final String name;
	private final Integer sort;
}
