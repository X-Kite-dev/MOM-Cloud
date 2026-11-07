package com.kitex.mom.wms.service.overreturn;

import com.kitex.common.security.component.PermissionService;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.dto.MdmFactoryAccessCheck;
import com.kitex.mom.mdm.feign.RemoteMdmFactoryAccessService;
import com.kitex.mom.wms.service.WmsAllocationMobileReceiptService;
import com.kitex.mom.wms.service.bill.WmsBillPermissionProofs;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.*;

import static com.kitex.mom.wms.service.overreturn.WmsOverReturnRules.*;

/**
 * 返还只接受当前实名工厂和原单实际仓的菜单树来源，不接受全局免仓权限。
 */
@Component
@RequiredArgsConstructor
public class WmsOverReturnAccess {
	private final PermissionService permissions;
	private final WmsBillPermissionProofs proofs;
	private final RemoteMdmFactoryAccessService factories;

	/**
	 * 当前真实组织及人员；无客户端传入身份。
	 */
	public record Scope(Long tenant, String factory, Long actor, String operator, String name) {
	}

	/**
	 * 验证真实正数人员租户与MDM实际工厂授权。
	 */
	public Scope scope() {
		var s = WmsAllocationMobileReceiptService.requireScope();
		var u = SecurityUtils.getUser();
		require(u.getId() != null && u.getId() > 0 && Objects.equals(s.tenantId(), u.getTenantId()),
				"返还需要真实正数用户身份");
		var r = factories.check(new MdmFactoryAccessCheck().setUserId(u.getId()).setFactoryCode(s.factoryCode())
				.setRequiredPermission("read"));
		require(r != null && r.isOk() && Boolean.TRUE.equals(r.getData()), "当前实际工厂未授权");
		return new Scope(s.tenantId(), s.factoryCode(), u.getId(), s.operatorCode(), u.getName());
	}

	/**
	 * 新写与永久结束都须有当前工厂写权限；纯查询不扩大资格。
	 */
	public void writeFactory(Scope s) {
		var r = factories.check(new MdmFactoryAccessCheck().setUserId(s.actor()).setFactoryCode(s.factory())
				.setRequiredPermission("write"));
		require(r != null && r.isOk() && Boolean.TRUE.equals(r.getData()), "当前工厂无写入岗位授权");
	}

	/**
	 * 核实名权限与实际仓根、客户端、角色菜单交集。
	 */
	public boolean allowed(String warehouse, String action, boolean target) {
		text(warehouse, 100, "实际仓库");
		String family = target ? "allocation_receive" : "allocation";
		String base = (target ? "wms_wmsAllocationReceive_" : "wms_wmsAllocationBill_") + action;
		List<WmsBillPermissionProofs.Check> candidates = new ArrayList<>();
		for (String candidate : List.of(base, base + "_" + warehouse)) {
			if (permissions.hasPermission(candidate)) {
				candidates.add(new WmsBillPermissionProofs.Check(family, action, warehouse, candidate));
			}
		}
		proofs.prefetch(candidates);
		return candidates.stream()
				.anyMatch(c -> proofs.hasProof(c.family(), c.action(), c.warehouseCode(), c.permission()));
	}

	/**
	 * 核原调出仓查看或执行岗位。
	 */
	public void source(String warehouse, boolean write) {
		require(allowed(warehouse, "cancel", false) || (!write && allowed(warehouse, "view", false)),
				"未获得原调出仓返还岗位授权");
	}

	/**
	 * 核实际目标仓接收岗位及原差异处置权限。
	 */
	public void target(String warehouse) {
		require(permissions.hasPermission("wms_transferVariance_dispose") && allowed(warehouse, "rec", true),
				"未获得原目标仓差异处置及接收岗位授权");
	}
}
