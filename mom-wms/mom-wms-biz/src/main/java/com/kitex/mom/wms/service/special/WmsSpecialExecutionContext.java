package com.kitex.mom.wms.service.special;

import com.kitex.mom.wms.entity.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.*;
import java.util.function.Supplier;

import static com.kitex.mom.wms.service.special.WmsSpecialRules.*;

/**
 * 特殊来源只能由已锁定申请的专用事务生成；不跨线程传播。
 */
public final class WmsSpecialExecutionContext {
	public static final String DOCUMENT = "wms_special_order";

	private record Bound(Long order, String type, Map<Object, WmsInoutFlowEntity> sources, boolean quarantine) {
	}

	private static final ThreadLocal<Bound> CURRENT = new ThreadLocal<>();

	private WmsSpecialExecutionContext() {
	}

	static <T> T execute(Long order, String type, Map<Object, WmsInoutFlowEntity> sources, Supplier<T> work) {
		return execute(order, type, sources, false, work);
	}

	static <T> T execute(Long order, String type, Map<Object, WmsInoutFlowEntity> sources, boolean quarantine,
	                     Supplier<T> work) {
		require(CURRENT.get() == null && order != null &&
						TransactionSynchronizationManager.isActualTransactionActive(),
				"特殊收发须在已锁定原申请的统一事务内记账");
		var frozen = new IdentityHashMap<Object, WmsInoutFlowEntity>();
		frozen.putAll(sources);
		CURRENT.set(new Bound(order, type, frozen, quarantine));
		try {
			return work.get();
		} finally {
			CURRENT.remove();
		}
	}

	public static void check(String type, Long order, String document) {
		var context = CURRENT.get();
		if (context == null && !DOCUMENT.equals(document)) {
			return;
		}
		require(context != null && DOCUMENT.equals(document) && Objects.equals(order, context.order()) &&
						Objects.equals(type, context.type()) &&
						TransactionSynchronizationManager.isActualTransactionActive(),
				"特殊收发请从原申请办理，不能通过普通收发入口指定来源");
	}

	private static WmsInoutFlowEntity source(Object detail) {
		var context = CURRENT.get();
		return context == null ? null : context.sources().get(detail);
	}

	/**
	 * 返回受控事务按明细对象绑定的原发出流水，供入库重复校验区分不同原件。
	 * 普通收货和未绑定明细返回空值；请求字段或expansion不能提供该来源证明。
	 *
	 * @param detail 本次实际收回入库明细，必须是专用编排已绑定的同一对象
	 * @return 真实原流水主键；普通入库或正向特殊收货为空
	 */
	public static Long inboundSourceFlowId(WmsInBillDetailEntity detail) {
		var source = source(detail);
		if (source == null) {
			return null;
		}
		require(TransactionSynchronizationManager.isActualTransactionActive() &&
				source.getId() != null && source.getId() > 0, "原件收回的受控事务或原流水身份无效");
		return source.getId();
	}

	public static void restoreInbound(WmsInBillDetailEntity detail, WmsLiveStockEntity stock) {
		var source = source(detail);
		if (source == null) {
			return;
		}
		require(source.getStockInTime() != null, "原出库流水缺少首次入库时间，不能重建退回库存");
		require(Boolean.TRUE.equals(source.getSerialManaged()) == Boolean.TRUE.equals(stock.getSerialManaged()),
				"当前标识规则与原实物单件属性不一致，请核对原件");
		WmsSpecialStockProof.copySource(source, stock);
		stock.setInTime(source.getStockInTime());
		if (CURRENT.get().quarantine()) {
			stock.setStatus(1);
			stock.setQualityStatus("ISOLATED");
			stock.setQualityResult(null);
			stock.setIsQualityTest("1");
			stock.setQualityHoldNo("SPECIAL-RETURN:" + CURRENT.get().order());
			stock.setQualitySourceType("WMS_SPECIAL_RETURN");
			stock.setQualitySourceId(CURRENT.get().order().toString());
		}
	}

	public static void restoreInboundFlow(WmsInBillDetailEntity detail, WmsInoutFlowEntity flow,
	                                      WmsLiveStockEntity stock) {
		flow.setStockInTime(stock.getInTime());
		var source = source(detail);
		if (source == null) {
			return;
		}
		WmsSpecialStockProof.copyFirstSource(stock, flow);
		flow.setSourceFlowId(source.getId());
		flow.setStockProofType("SPECIAL_RETURN");
	}

	public static void markOutboundFlow(WmsOutBillDetailEntity detail, WmsInoutFlowEntity flow) {
		var source = source(detail);
		if (source != null) {
			flow.setSourceFlowId(source.getId());
			flow.setStockProofType("SPECIAL_RETURN");
		}
	}
}
