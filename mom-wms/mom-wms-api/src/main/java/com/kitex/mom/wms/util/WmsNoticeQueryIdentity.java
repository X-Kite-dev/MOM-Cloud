package com.kitex.mom.wms.util;

import com.kitex.common.core.exception.CheckedException;

/**
 * 通知主表、收发单及其明细的原通知查询身份，统一字符串入参与数据库Long绑定。
 * 不负责扩大业务类型、组织或权限范围，也不将通知主键转换成文本模糊条件。
 */
public final class WmsNoticeQueryIdentity {

	private WmsNoticeQueryIdentity() {
	}

	/**
	 * 解析可选的完整原通知主键；保留对外字符串合同，不做浮点或截断转换。
	 *
	 * @param input 原通知主键文本，空值或纯空白表示不按通知筛选
	 * @return 未填写时返回null，否则返回正Long主键
	 * @throws CheckedException 非正整数字面量或超出Long范围时拒绝查询
	 */
	public static Long optionalId(String input) {
		if (input == null || input.isBlank()) {
			return null;
		}
		String value = input.trim();
		if (!value.matches("[1-9][0-9]{0,18}")) {
			throw new CheckedException("通知单号格式不正确，请填写完整的通知单号");
		}
		try {
			return Long.valueOf(value);
		} catch (NumberFormatException ex) {
			throw new CheckedException("通知单号格式不正确，请填写完整的通知单号");
		}
	}

}
