package com.kitex.mom.wms.service.impl.externalinventory;

import com.kitex.mom.wms.dto.WmsExternalInventoryLossDTO.*;
import com.kitex.mom.wms.dto.WmsInventoryApprovalRecoveryDTO.Scope;
import com.kitex.mom.wms.service.WmsWarehouseBusinessGuard;
import com.kitex.mom.wms.service.inventoryapproval.WmsInventoryApprovalRecoveryAccess;
import com.kitex.mom.wms.service.inventoryapproval.WmsInventoryApprovalRecoveryAccess.Identity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.*;

import static com.kitex.mom.wms.service.impl.WmsInventoryPlanAccess.require;
import static com.kitex.mom.wms.service.impl.externalinventory.ExternalLossStore.str;

/**
 * 使用既有实名厂仓证明；历史读取、首次动作与永久结束各自核权。
 */
@Component
@RequiredArgsConstructor
public class ExternalLossAccess {
	private final WmsInventoryApprovalRecoveryAccess access;
	private final WmsWarehouseBusinessGuard guard;
	private final ExternalLossStore store;

	Identity identity() {
		return access.identity();
	}

	/**
	 * 按原业务对象和真实仓按钮来源校验读取权限，禁止客户端放大工厂和人员范围。
	 */
	Map<String, Object> read(Identity i, DetailScope s, boolean mandate) {
		require(i.tenant().toString().equals(s.tenantId()) && i.actor().toString().equals(s.actorId()) &&
						i.factory().equals(s.factoryCode()),
				"外部原号组织或实名人员不符");
		if (!mandate) {
			return access.bind(i, new Scope(s.tenantId(), s.actorId(), s.factoryCode(), s.warehouseCode(), s.planId(),
					s.planDetailId()));
		}
		require(access.allowed(s.warehouseCode(), "owner_view") || access.allowed(s.warehouseCode(), "owner_manage")
				|| access.allowed(s.warehouseCode(), "owner_review"), "无本仓货主授权读取来源");
		return Map.of();
	}

	/**
	 * 未受理动作使用同仓hand或edit及工厂写权，授权域再分提议和审核职责。
	 */
	void job(Identity i, DetailScope s, String domain, String action) {
		access.write(i, s.warehouseCode());
		if ("OM1".equals(domain)) {
			require(access.allowed(s.warehouseCode(), "PROPOSE".equals(action) ? "owner_manage" : "owner_review"),
					"无本动作仓级授权岗位");
		}
	}

	/**
	 * 仅新业务写入核仓库盘点配置及当前真实移动入口开关。
	 */
	void operation(String wh, boolean mobile) {
		require(guard.evaluate(wh, "other_inventory", true).allowed() &&
						guard.evaluate(wh, "other_inventory", false).allowed(),
				"本仓盘点业务未启用");
		if (mobile) {
			guard.checkMobile(wh);
		}
	}

	DetailScope scope(Identity i, String detail) {
		var r = access.detail(i, detail);
		return new DetailScope(i.tenant().toString(), i.actor().toString(), i.factory(), str(r, "warehouse_code"),
				str(r, "plan_id"), detail);
	}

	boolean canHandle(String wh) {
		return access.executable(wh);
	}

	/**
	 * 控制盘点期间账面及原件可见性，当前损益岗位来自实际仓级按钮来源。
	 */
	void visible(Identity i, DetailScope s) {
		read(i, s, false);
		// 实盘进行中仅向有实际损益岗位者透露账面、原件及候选，防止普通盘点录入反查盲盘。
		var plan = store.one(
				"select status from mom_wms.wms_inside_inventory_plan where id=? and tenant_id=? and factory_code=? and warehouse_code=?",
				Long.valueOf(s.planId()), i.tenant(), i.factory(), s.warehouseCode());
		require("1".equals(str(plan, "status")) || canHandle(s.warehouseCode()), "盲盘阶段无权查看损益事实");
	}

	/**
	 * 生成仅属于当前实名人员的原号，分别列出首次执行和永久结束的可用动作。
	 */
	Context<?> context(String domain, String key, boolean mobile) {
		var i = identity();
		boolean om = "OM1".equals(domain);
		var s = om ? new DetailScope(i.tenant().toString(), i.actor().toString(), i.factory(), key, null, null) : scope(
				i, key);
		var row = read(i, s, om);
		var actions = om ? List.of("PROPOSE", "APPROVE", "REJECT", "REVOKE") : "OE1".equals(domain) ? List.of(
				"REGISTER") :
				List.of("SUBMIT", "APPROVE", "REJECT", "REVOKE", "POST");
		var end = new ArrayList<String>();
		if (canHandle(s.warehouseCode()) && access.factory(i, "write")) {
			for (String a : actions) {
				if (!om || access.allowed(s.warehouseCode(), "PROPOSE".equals(a) ? "owner_manage" : "owner_review")) {
					end.add(a);
				}
			}
		}
		List<String> execute = List.copyOf(end);
		if (!om) {
			try {
				operation(s.warehouseCode(), mobile);
			} catch (com.kitex.common.core.exception.CheckedException unavailable) {
				// 恢复和END只需原号真实读写权，不被今日手机或盘点业务配置拦截。
				execute = List.of();
			}
			if (!execute.isEmpty()) {
				var stage = store.one("""
						select p.status,p.handle_state,p.del_flag as plan_deleted,d.status as detail_status,d.pl_status,d.del_flag as detail_deleted
						from mom_wms.wms_inside_inventory_plan p join mom_wms.wms_inside_inventory_plan_detail d
						on d.plan_id=p.id and d.tenant_id=p.tenant_id and d.factory_code=p.factory_code and d.warehouse_code=p.warehouse_code
						where d.id=? and d.tenant_id=? and d.factory_code=? and d.warehouse_code=?
						""", Long.valueOf(s.planDetailId()), i.tenant(), i.factory(), s.warehouseCode());
				if (!"1".equals(str(stage, "status")) || !"0".equals(str(stage, "handle_state")) ||
						!"1".equals(str(stage, "detail_status"))
						|| !"0".equals(str(stage, "pl_status")) || !"0".equals(str(stage, "plan_deleted")) ||
						!"0".equals(str(stage, "detail_deleted"))) {
					execute = List.of();
				}
			}
		}
		Object wire = om ? new WarehouseScope(s.tenantId(), s.actorId(), s.factoryCode(), s.warehouseCode()) : s;
		return new Context<>(1, domain, wire, i.actorCode(),
				domain + "_" + s.actorId() + "_" + UUID.randomUUID().toString().replace("-", ""),
				str(row, "plan_no"), str(row, "mat_code"), str(row, "mat_unit"), execute, end);
	}
}
