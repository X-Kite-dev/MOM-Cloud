package com.kitex.mom.wms.service.inventoryapproval;

import com.kitex.common.security.component.PermissionService;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.dto.MdmFactoryAccessCheck;
import com.kitex.mom.mdm.feign.RemoteMdmFactoryAccessService;
import com.kitex.mom.wms.dto.WmsInventoryApprovalRecoveryDTO.Scope;
import com.kitex.mom.wms.service.WmsAllocationMobileReceiptService;
import com.kitex.mom.wms.service.bill.WmsBillPermissionProofs;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

import static com.kitex.mom.wms.service.impl.WmsInventoryPlanAccess.require;

/**
 * 真实MDM工厂与实际仓菜单树证明；历史读取包含逻辑删除父行，不借今日作业状态扩大权限。
 */
@Component
@RequiredArgsConstructor
public class WmsInventoryApprovalRecoveryAccess {
	private final PermissionService permissions;
	private final WmsBillPermissionProofs proofs;
	private final RemoteMdmFactoryAccessService factories;
	private final JdbcTemplate jdbc;

	/**
	 * 当前实名身份；所有值来自认证，禁止上传身份取代。
	 */
	public record Identity(Long tenant, Long actor, String factory, String actorCode) {
	}

	/**
	 * 核实非skip组织与MDM实际工厂读资格。
	 */
	public Identity identity() {
		var base = WmsAllocationMobileReceiptService.requireScope();
		var user = SecurityUtils.getUser();
		require(user != null && user.getId() != null && user.getId() > 0
				&& Objects.equals(user.getTenantId(), base.tenantId()), "需要当前实名人员");
		var i = new Identity(base.tenantId(), user.getId(), base.factoryCode(), base.operatorCode());
		require(factory(i, "read"), "实际工厂无读取授权");
		return i;
	}

	/**
	 * 工厂岗位独立于仓级菜单树来源。
	 */
	public boolean factory(Identity i, String permission) {
		var response = factories.check(new MdmFactoryAccessCheck().setUserId(i.actor()).setFactoryCode(i.factory())
				.setRequiredPermission(permission));
		return response != null && response.isOk() && Boolean.TRUE.equals(response.getData());
	}

	/**
	 * 仅核原真实父范围，不使用活动盘点门禁或逻辑删除过滤损坏历史可恢复性。
	 */
	public Map<String, Object> detail(Identity i, String detailId) {
		var rows = jdbc.queryForList(
				"select d.*,p.plan_no,p.warehouse_code as plan_warehouse_code from mom_wms.wms_inside_inventory_plan_detail d join mom_wms.wms_inside_inventory_plan p on p.id=d.plan_id and p.tenant_id=d.tenant_id and p.factory_code=d.factory_code where d.tenant_id=? and d.factory_code=? and d.id=?",
				i.tenant(), i.factory(), WmsInventoryApprovalRecoveryCodec.id(detailId));
		require(rows.size() == 1, "原盘点范围不存在或不属于当前组织");
		var row = rows.get(0);
		require(Objects.equals(row.get("warehouse_code"), row.get("plan_warehouse_code")), "计划与明细实际仓不一致");
		return row;
	}

	/**
	 * 六项范围绑定实际人员、实际父行和实际仓；原审批关联在首次执行另核。
	 */
	public Map<String, Object> bind(Identity i, Scope s) {
		require(i.tenant().toString().equals(s.tenantId()) && i.actor().toString().equals(s.actorId())
				&& i.factory().equals(s.factoryCode()), "原号人员或实际组织不一致");
		var row = detail(i, s.planDetailId());
		require(Objects.equals(row.get("warehouse_code"), s.warehouseCode())
				&& row.get("plan_id").toString().equals(s.planId()), "原号盘点范围不一致");
		require(allowed(s.warehouseCode(), "view") || executable(s.warehouseCode()), "无原实际仓读取来源");
		return row;
	}

	/**
	 * hand与edit必须分别证明同一实际仓来源，旧global edit不能变成任意仓。
	 */
	public boolean executable(String warehouse) {
		return allowed(warehouse, "hand") || allowed(warehouse, "edit");
	}

	/**
	 * 新执行及结束须真实岗位与实际工厂写权，不受今日手机开关影响。
	 */
	public void write(Identity i, String warehouse) {
		require(executable(warehouse) && factory(i, "write"), "无原实际仓执行岗位或工厂写授权");
	}

	/**
	 * 核同一实际仓内的指定盘点按钮来源，供外部盘亏授权域复用。
	 */
	public boolean allowed(String warehouse, String action) {
		String base = "wms_wmsInsideInventoryPlan_" + action;
		List<WmsBillPermissionProofs.Check> checks = new ArrayList<>();
		for (String p : List.of(base, base + "_" + warehouse)) {
			if (permissions.hasPermission(p)) {
				checks.add(new WmsBillPermissionProofs.Check("inventory", action, warehouse, p));
			}
		}
		proofs.prefetch(checks);
		return checks.stream()
				.anyMatch(c -> proofs.hasProof(c.family(), c.action(), c.warehouseCode(), c.permission()));
	}
}
