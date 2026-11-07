package com.kitex.mom.wms.service.inventoryapproval;

import java.util.Objects;

import com.kitex.mom.wms.dto.WmsInventoryApprovalRecoveryDTO.Scope;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.function.Supplier;

import static com.kitex.mom.wms.service.impl.WmsInventoryPlanAccess.require;

/**
 * 仅本包可信编排可建立的线程执行凭据；不向Controller公开skip开关。
 */
public final class WmsInventoryApprovalExecutionContext {
	private static final ThreadLocal<Intent> CURRENT = new ThreadLocal<>();

	/**
	 * 已核原号、动作、目标审批及不可变真实组织明细范围，不包含令牌。
	 */
	private record Intent(String request, String action, Long approval, Scope scope) {
	}

	private WmsInventoryApprovalExecutionContext() {
	}

	/**
	 * 原正式申请入口核验受托原号，普通客户端无法建立此凭据。
	 */
	public static boolean submitAllowed(String request) {
		Intent i = CURRENT.get();
		return i != null && "SUBMIT".equals(i.action()) && Objects.equals(request, i.request());
	}

	/**
	 * 仅桥接当前受托SUBMIT已核的真实仓hand/edit证明；明细固定在同包编排持锁核实的scope中。
	 * 原helper其他动作和所有旧调用不获得此资格，结束/审核及事务外也不开放。
	 */
	public static boolean submitWarehouseHand(String warehouse) {
		Intent i = CURRENT.get();
		var actor = SecurityUtils.getUser();
		return i != null && "SUBMIT".equals(i.action()) && i.approval() == null
				&& TransactionSynchronizationManager.isActualTransactionActive()
				&& !Boolean.TRUE.equals(TenantContextHolder.getTenantSkip())
				&& !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())
				&& actor != null && actor.getId() != null &&
				Objects.equals(actor.getId().toString(), i.scope().actorId())
				&& Objects.equals(String.valueOf(TenantContextHolder.getTenantId()), i.scope().tenantId())
				&& Objects.equals(FactoryContextHolder.getFactoryCode(), i.scope().factoryCode())
				&& Objects.equals(warehouse, i.scope().warehouseCode())
				&& i.scope().planDetailId() != null && i.scope().planId() != null;
	}

	/**
	 * 新域申请的旧审核入口必须处在同一可信编排及目标动作中。
	 */
	public static boolean reviewAllowed(Long approval, String action) {
		Intent i = CURRENT.get();
		return i != null && Objects.equals(approval, i.approval()) && Objects.equals(action, i.action());
	}

	/**
	 * 同包内建立一次性受托调用，失败和正常退出均清除，不传播线程池。
	 */
	static <T> T call(String request, String action, Long approval, Scope scope, Supplier<T> work) {
		require(CURRENT.get() == null, "审批受托上下文不能嵌套");
		require(TransactionSynchronizationManager.isActualTransactionActive() && scope != null,
				"受托审批必须在已验证范围的活动事务内");
		CURRENT.set(new Intent(request, action, approval, scope));
		try {
			return work.get();
		} finally {
			CURRENT.remove();
		}
	}
}
