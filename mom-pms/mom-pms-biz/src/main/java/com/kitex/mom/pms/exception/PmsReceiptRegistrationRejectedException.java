package com.kitex.mom.pms.exception;

import com.kitex.common.core.exception.CheckedException;

/**
 * 现单实收登记自身校验或条件写入不成立的明确领域拒绝。
 * 只能在登记事务退出并确认回滚后转为业务失败回执；数据库、提交、网络等异常不能包装为此类型。
 */
public class PmsReceiptRegistrationRejectedException extends CheckedException {

	private static final long serialVersionUID = 1L;

	/**
	 * @param message 可直接告知操作员的原单登记拒绝原因，不携带凭据或连接信息
	 */
	public PmsReceiptRegistrationRejectedException(String message) {
		super(message);
	}
}
