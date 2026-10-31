package com.kitex.mom.wms.service.organization;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.security.component.PermissionService;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.dto.MdmFactoryAccessCheck;
import com.kitex.mom.mdm.entity.MdmWarehouseEntity;
import com.kitex.mom.mdm.feign.RemoteMdmFactoryAccessService;
import com.kitex.mom.mdm.feign.RemoteWarehouseService;
import com.kitex.mom.wms.dto.WmsOrganizationTransferDTO.Action;
import com.kitex.mom.wms.service.WmsAllocationMobileReceiptService;
import com.kitex.mom.wms.service.WmsWarehouseBusinessGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Objects;

import static com.kitex.mom.wms.service.organization.WmsOrganizationTransferRules.require;

/**
 * 每端只在自己的实际工厂及仓库办理；请求参数不产生对另一工厂的访问权。
 */
@Service
@RequiredArgsConstructor
public class WmsOrganizationTransferAccess {
	private final PermissionService permissions;
	private final RemoteMdmFactoryAccessService factories;
	private final RemoteWarehouseService warehouses;
	private final WmsWarehouseBusinessGuard business;

	public record Scope(Long tenant, String factory, String warehouse, String operator, Long userId) {
	}

	public Scope scope(String warehouse, boolean write) {
		var actual = WmsAllocationMobileReceiptService.requireScope();
		require(warehouse != null && !warehouse.isBlank() && warehouse.length() <= 100
						&& warehouse.equals(warehouse.trim()) && !warehouse.matches(".*[?*].*"),
				"请从实际仓库的组织调拨入口办理");
		var user = SecurityUtils.getUser();
		require(user.getId() != null && Objects.equals(user.getTenantId(), actual.tenantId()),
				"组织调拨需要可核验的实名租户身份");
		var authorized = factories.check(
				new MdmFactoryAccessCheck().setUserId(user.getId()).setFactoryCode(actual.factoryCode())
						.setRequiredPermission(write ? "write" : "read"));
		require(authorized != null && authorized.isOk() && Boolean.TRUE.equals(authorized.getData()),
				"未获得当前实际工厂授权");
		var scope = new Scope(actual.tenantId(), actual.factoryCode(), warehouse, actual.operatorCode(), user.getId());
		permission(scope, "view");
		return scope;
	}

	public boolean allowed(Scope scope, String action) {
		String permission = "wms_orgTransfer_" + action;
		return permissions.hasPermission(permission, permission + "_" + scope.warehouse());
	}

	public void permission(Scope scope, String action) {
		require(allowed(scope, action), "当前账号没有该仓库的组织调拨" + label(action) + "权限");
	}

	private String label(String action) {
		return switch (action) {
			case "view" -> "查看";
			case "add" -> "申请";
			case "approve" -> "确认";
			case "ship" -> "发出";
			case "receive" -> "接收";
			case "return" -> "实物退回";
			case "exception" -> "差异处理";
			case "cancel" -> "作废";
			case "config" -> "配置维护";
			default -> "操作";
		};
	}

	public void action(Scope scope, Action action) {
		permission(scope, switch (action) {
			case CREATE -> "add";
			case CONFIRM -> "approve";
			case SHIP -> "ship";
			case RECEIVE, REQUEST_RETURN -> "receive";
			case RETURN -> "return";
			case CANCEL -> "cancel";
			case PROPOSE_EXCEPTION, APPROVE_EXCEPTION, REJECT_EXCEPTION -> "exception";
		});
	}

	public MdmWarehouseEntity warehouse(Scope scope) {
		var response = warehouses.getByCode(scope.warehouse());
		var row = response == null ? null : response.getData();
		require(response != null && response.isOk() && row != null && Boolean.TRUE.equals(row.getStatus())
				&& Objects.equals(row.getTenantId(), scope.tenant()) &&
				Objects.equals(row.getFactoryCode(), scope.factory())
				&& Objects.equals(row.getWarehouseCode(), scope.warehouse()), "当前仓库不存在、停用或组织不符");
		return row;
	}

	public void configured(Scope scope) {
		business.checkOrganizationTransfer(scope.warehouse());
	}

	public void checkMobile(Scope scope) {
		business.checkMobile(scope.warehouse());
	}

	public boolean mobileEnabled(Scope scope) {
		return business.mobileEnabled(scope.warehouse());
	}
}
