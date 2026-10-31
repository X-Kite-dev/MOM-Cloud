package com.kitex.mom.wms.enums.menu;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;
import java.util.Set;

/** 生产退料实收能力独立配置；权限始终落到实际仓库。 */
@Getter
@AllArgsConstructor
public enum ReturnReceiptActionEnum {
	VIEW("view", "实收详情", 110),
	FINALIZE("finalize", "结束收货", 120),
	DISPOSE("dispose", "差异处置", 130),
	RETRY("retry", "回写续跑", 140);

	private final String code;
	private final String label;
	private final int sort;

	public String configurationValue() {
		return "wms:return_receipt:" + code;
	}

	public String permission() {
		return "wms_return_receipt_" + code;
	}

	public String permission(String warehouse) {
		return permission() + "_" + warehouse;
	}

	public static boolean isConfigurationValue(String value) {
		return Arrays.stream(values()).anyMatch(action -> action.configurationValue().equals(value));
	}

	public static boolean validSelection(Set<String> selection) {
		boolean selected = Arrays.stream(values()).anyMatch(action -> selection.contains(action.configurationValue()));
		return !selected || (selection.contains(MenuInBizTypeEnum.RETURN.getValue())
				&& selection.contains(VIEW.configurationValue()));
	}
}
