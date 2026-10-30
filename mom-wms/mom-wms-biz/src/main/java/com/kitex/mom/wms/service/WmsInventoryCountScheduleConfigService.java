package com.kitex.mom.wms.service;

import cn.hutool.core.util.StrUtil;
import com.kitex.common.core.util.R;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.entity.MdmWarehouseEntity;
import com.kitex.mom.mdm.feign.RemoteWarehouseService;
import com.kitex.mom.wms.mapper.WmsInventoryCountScheduleMapper;
import com.kitex.mom.wms.mapper.WmsInventoryCountTemplateMapper;
import com.kitex.mom.wms.mapper.WmsWarehouseMenuMapper;
import com.kitex.mom.wms.service.impl.WmsInventoryWarehousePermission;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

import static com.kitex.mom.wms.service.impl.WmsInventoryPlanAccess.factory;
import static com.kitex.mom.wms.service.impl.WmsInventoryPlanAccess.require;

/** 用户配置盘点周期；任务执行方只能读取已保存的当前组织规则。 */
@Service
@RequiredArgsConstructor
public class WmsInventoryCountScheduleConfigService {
	private final WmsInventoryCountScheduleMapper schedules;
	private final WmsInventoryCountTemplateMapper templates;
	private final WmsWarehouseMenuMapper menus;
	private final WmsInventoryWarehousePermission inventoryPermission;
	private final PermissionService permissions;
	private final RemoteWarehouseService warehouses;

	public Schedule get(String warehouseCode, String templateCode) {
		Scope scope = scope(warehouseCode, templateCode);
		requireTemplate(scope);
		var row = schedules.find(scope.tenant(), scope.factory(), scope.templateCode(), scope.warehouse());
		return row == null ? new Schedule(0, false, null, null, null, null) : read(row);
	}

	@Transactional(rollbackFor = Exception.class)
	public Schedule save(SaveCommand command) {
		return saveAt(command, LocalDateTime.now(ZoneOffset.UTC));
	}

	Schedule saveAt(SaveCommand command, LocalDateTime nowUtc) {
		require(command != null, "请填写盘点周期");
		Scope scope = scope(command.warehouseCode(), command.templateCode());
		require(permissions.hasPermission("wms_wmsWarehouseMenu_add"), "无仓库配置权限");
		require(command.expectedVersion() >= 0, "盘点周期版本无效");
		LocalDateTime next = WmsInventoryCountScheduleService.nextDueAfter(nowUtc,
				command.intervalDays(), command.timeOfDay(), command.zoneId());
		menus.lockConfiguration("WMS_WAREHOUSE_MENU:" + scope.tenant() + ":" + scope.factory());
		var template = requireTemplate(scope);
		if (command.enabled()) {
			require(template.enabled(), "盘点模板停用时不能启用周期");
			requireActiveWarehouse(scope);
		}
		var previous = schedules.find(scope.tenant(), scope.factory(), scope.templateCode(), scope.warehouse());
		int version = previous == null ? 0 : previous.version();
		require(version == command.expectedVersion() && version < Integer.MAX_VALUE,
				"盘点周期已更新，请刷新后重试");
		var user = SecurityUtils.getUser();
		require(user != null && StrUtil.isNotBlank(user.getUsername()), "无法识别当前操作人");
		LocalDateTime nextRunUtc = command.enabled() ? next : null;
		require(schedules.upsert(scope.tenant(), scope.factory(), scope.templateCode(), scope.warehouse(),
				command.enabled(), command.intervalDays(), command.timeOfDay(), command.zoneId(),
				nextRunUtc, version, user.getUsername()) == 1,
				"盘点周期已更新，请刷新后重试");
		return new Schedule(version + 1, command.enabled(), command.intervalDays(),
				command.timeOfDay(), command.zoneId(), nextRunUtc);
	}

	private Scope scope(String warehouseCode, String templateCode) {
		String warehouse = StrUtil.trim(warehouseCode);
		String code = StrUtil.trim(templateCode);
		require(StrUtil.isNotBlank(warehouse) && StrUtil.isNotBlank(code)
				&& code.matches("[A-Z][A-Z0-9_]{1,31}"), "请选择当前仓库的盘点模板");
		inventoryPermission.require("add", warehouse);
		return new Scope(TenantContextHolder.getTenantId(), factory(), warehouse, code);
	}

	private WmsInventoryCountTemplateMapper.TemplateRow requireTemplate(Scope scope) {
		var template = templates.find(scope.tenant(), scope.factory(), scope.templateCode());
		require(template != null && Objects.equals(scope.warehouse(), template.warehouseCode()),
				"盘点模板不存在或不属于当前仓库");
		return template;
	}

	private void requireActiveWarehouse(Scope scope) {
		R<MdmWarehouseEntity> response = warehouses.getByCode(scope.warehouse());
		require(response != null && response.isOk(), "盘点仓库读取失败");
		var warehouse = response.getData();
		require(warehouse != null && Objects.equals(scope.tenant(), warehouse.getTenantId())
				&& Objects.equals(scope.factory(), warehouse.getFactoryCode())
				&& Objects.equals(scope.warehouse(), warehouse.getWarehouseCode())
				&& Boolean.TRUE.equals(warehouse.getStatus()),
				"仅允许在当前工厂已启用仓库配置盘点周期");
	}

	private Schedule read(WmsInventoryCountScheduleMapper.ScheduleRow row) {
		require(row.version() >= 1 && row.intervalDays() >= 1 && row.intervalDays() <= 365,
				"盘点周期配置结构无效");
		WmsInventoryCountScheduleService.nextDueAfter(LocalDateTime.now(ZoneOffset.UTC),
				row.intervalDays(), row.timeOfDay(), row.zoneId());
		require(row.enabled() == (row.nextRunUtc() != null), "盘点周期状态无效");
		return new Schedule(row.version(), row.enabled(), row.intervalDays(), row.timeOfDay(),
				row.zoneId(), row.nextRunUtc());
	}

	private record Scope(Long tenant, String factory, String warehouse, String templateCode) {
	}

	public record SaveCommand(String warehouseCode, String templateCode, boolean enabled,
			int intervalDays, String timeOfDay, String zoneId, int expectedVersion) {
	}

	public record Schedule(int version, boolean enabled, Integer intervalDays,
			String timeOfDay, String zoneId, LocalDateTime nextRunUtc) {
	}
}
