package com.kitex.mom.wms.service.organization;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.wms.dto.WmsOrganizationTransferDTO.Action;

import java.math.BigDecimal;
import java.util.Set;

/**
 * 两端职责与数量守恒；任何配置不得绕过租户/工厂、实物或货权约束。
 */
public final class WmsOrganizationTransferRules {
	private WmsOrganizationTransferRules() {
	}

	public static String next(String state, Action action, boolean source) {
		boolean pre = Set.of("PENDING_CONFIRMATION", "READY_TO_SHIP").contains(state);
		return switch (action) {
			case CONFIRM -> {
				require(!source && state.equals("PENDING_CONFIRMATION"), "仅接收方可确认待确认调拨");
				yield "READY_TO_SHIP";
			}
			case SHIP -> {
				require(source && state.equals("READY_TO_SHIP"), "仅发出方可发运已确认调拨");
				yield "IN_TRANSIT";
			}
			case RECEIVE -> {
				require(!source && Set.of("IN_TRANSIT", "PARTIALLY_RECEIVED").contains(state), "当前状态不允许接收");
				yield state;
			}
			case CANCEL -> {
				require(pre, "已发出调拨不能直接作废，请按实物退回办理");
				yield "CANCELLED";
			}
			case REQUEST_RETURN -> {
				require(!source && Set.of("IN_TRANSIT", "PARTIALLY_RECEIVED").contains(state),
						"仅接收方可申请未收实物退回");
				yield "RETURN_PENDING";
			}
			case RETURN -> {
				require(source && state.equals("RETURN_PENDING"), "仅发出方可登记实际退回");
				yield state;
			}
			case PROPOSE_EXCEPTION -> {
				require(!source && Set.of("IN_TRANSIT", "PARTIALLY_RECEIVED", "RETURN_PENDING").contains(state),
						"仅接收方可申报未结差异");
				yield "EXCEPTION_PENDING";
			}
			case APPROVE_EXCEPTION, REJECT_EXCEPTION -> {
				require(source && state.equals("EXCEPTION_PENDING"), "仅发出方可复核待确认差异");
				yield state;
			}
			default -> throw new CheckedException("当前动作不能推进已有调拨");
		};
	}

	public static BigDecimal remaining(BigDecimal issued, BigDecimal received, BigDecimal returned,
	                                   BigDecimal exception) {
		require(issued != null && received != null && returned != null && exception != null
						&& issued.signum() > 0 && received.signum() >= 0 && returned.signum() >= 0 && exception.signum() >= 0,
				"组织调拨数量无效");
		BigDecimal remaining = issued.subtract(received).subtract(returned).subtract(exception);
		require(remaining.signum() >= 0, "接收、退回和差异数量超过发出量");
		return remaining;
	}

	public static String receiptState(BigDecimal remaining) {
		return remaining.signum() == 0 ? "COMPLETED" : "PARTIALLY_RECEIVED";
	}

	public static String returnState(BigDecimal remaining, BigDecimal received) {
		return remaining.signum() > 0 ? "RETURN_PENDING" : received.signum() > 0 ? "CLOSED_WITH_RETURN" : "RETURNED";
	}

	public static void companyAgreement(String source, String target, boolean sourceAllowed, boolean targetAllowed,
	                                    String agreement, String creator, String confirmer) {
		if (source.equals(target)) return;
		require(sourceAllowed && targetAllowed, "双方库存组织均须启用跨公司调拨");
		require(agreement != null && !agreement.isBlank(), "跨公司调拨必须提供业务依据");
		require(!creator.equals(confirmer), "跨公司调拨须由不同收发方操作人确认");
	}

	public static void require(boolean valid, String message) {
		if (!valid) throw new CheckedException(message);
	}
}
