package com.kitex.mom.wms.service.special;

import com.kitex.common.security.component.PermissionService;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.dto.MdmFactoryAccessCheck;
import com.kitex.mom.mdm.entity.MdmWarehouseEntity;
import com.kitex.mom.mdm.feign.RemoteMdmFactoryAccessService;
import com.kitex.mom.mdm.feign.RemoteWarehouseService;
import com.kitex.mom.wms.service.WmsAllocationMobileReceiptService;
import com.kitex.mom.wms.service.WmsWarehouseBusinessGuard;
import com.kitex.mom.wms.dto.WmsSpecialMovementDTO.Direction;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Objects;

import static com.kitex.mom.wms.service.special.WmsSpecialRules.*;

@Service
@RequiredArgsConstructor
public class WmsSpecialAccess {
	private final PermissionService permissions;
	private final RemoteMdmFactoryAccessService factories;
	private final RemoteWarehouseService warehouses;
	private final WmsWarehouseBusinessGuard business;

	public record Scope(Long tenant, String factory, String warehouse, String operator, Long userId) {
	}

	public Scope scope(String warehouse, boolean write) {
		field(warehouse, 100, true, "实际仓库");
		require(!warehouse.matches(".*[?*].*"), "请选择实际仓库");
		var actual = WmsAllocationMobileReceiptService.requireScope();
		var user = SecurityUtils.getUser();
		require(user.getId() != null && Objects.equals(user.getTenantId(), actual.tenantId()),
				"特殊收发需要实际租户的实名身份");
		var response = factories.check(
				new MdmFactoryAccessCheck().setUserId(user.getId()).setFactoryCode(actual.factoryCode())
						.setRequiredPermission(write ? "write" : "read"));
		require(response != null && response.isOk() && Boolean.TRUE.equals(response.getData()),
				"未获得当前工厂授权");
		var scope = new Scope(actual.tenantId(), actual.factoryCode(), warehouse, actual.operatorCode(),
				user.getId());
		requireAction(scope, "view");
		return scope;
	}

	public boolean allowed(Scope scope, String action) {
		var code = "wms_specialMovement_" + action;
		return permissions.hasPermission(code, code + "_" + scope.warehouse());
	}

	public void requireAction(Scope scope, String action) {
		require(allowed(scope, action), "当前账号没有该仓库的特殊收发操作权限");
	}

	public MdmWarehouseEntity snapshot(Scope scope) {
		var response = warehouses.getByCode(scope.warehouse());
		var row = response == null ? null : response.getData();
		require(response != null && response.isOk() && row != null &&
				Objects.equals(row.getTenantId(), scope.tenant()) &&
				Objects.equals(row.getFactoryCode(), scope.factory()) &&
				Objects.equals(row.getWarehouseCode(), scope.warehouse()), "实际仓库不存在或组织不一致");
		return row;
	}

	public MdmWarehouseEntity warehouse(Scope scope) {
		var row = snapshot(scope);
		require(Boolean.TRUE.equals(row.getStatus()), "当前仓库已停用，不能开始新的实物作业");
		return row;
	}

	public MdmWarehouseEntity checkOperation(Scope scope, Direction direction, boolean mobile) {
		var row = warehouse(scope);
		business.check(scope.warehouse(), direction == Direction.IN ? "other_in" : "other_out",
				direction == Direction.IN);
		if (mobile) {
			business.checkMobile(scope.warehouse());
		}
		return row;
	}

	public void configured(Scope scope) {
		business.checkSpecialMovement(scope.warehouse());
	}

	public boolean mobileEnabled(Scope scope) {
		return mobileEnabled(snapshot(scope));
	}

	public boolean mobileEnabled(MdmWarehouseEntity row) {
		require(row.getStatus() != null &&
						(row.getIsMobile() == null || row.getIsMobile() == 0 || row.getIsMobile() == 1),
				"实际仓库状态或手机设置无效");
		return Boolean.TRUE.equals(row.getStatus()) && Integer.valueOf(1).equals(row.getIsMobile());
	}
}
