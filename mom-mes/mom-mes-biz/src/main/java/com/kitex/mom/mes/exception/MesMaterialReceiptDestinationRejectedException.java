package com.kitex.mom.mes.exception;

import com.kitex.common.core.exception.CheckedException;

/**
 * 签收工位与已核实的 WMS 配送目的工位不符，签收和库存入账均未执行。
 * 同一事务中如已初始化历史签收基线，该初始化随拒绝一起回滚。
 */
public final class MesMaterialReceiptDestinationRejectedException extends CheckedException {

	private static final long serialVersionUID = 1L;

	/**
	 * 构造可直接展示给操作员的工位不匹配提示。
	 */
	public MesMaterialReceiptDestinationRejectedException() {
		super("签收工位必须与WMS配送目的工位一致，请按通知逐批签收");
	}
}
