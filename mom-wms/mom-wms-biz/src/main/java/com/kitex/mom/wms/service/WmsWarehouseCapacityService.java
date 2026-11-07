package com.kitex.mom.wms.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.feign.RemoteWarehouseLocationService;
import com.kitex.mom.mdm.feign.RemoteWarehouseService;
import com.kitex.mom.wms.dto.WmsWarehouseCapacityDTO.Model;
import com.kitex.mom.wms.dto.WmsWarehouseCapacityDTO.Location;
import com.kitex.mom.wms.dto.WmsWarehouseCapacityDTO.StockGroup;
import com.kitex.mom.wms.dto.WmsWarehouseCapacityDTO.Policy;
import com.kitex.mom.wms.dto.WmsWarehouseCapacityDTO.View;
import com.kitex.mom.wms.dto.WmsWarehouseCapacityDTO.SaveCommand;
import com.kitex.mom.wms.dto.WmsWarehouseCapacityDTO.Saved;
import com.kitex.mom.wms.dto.WmsWarehouseCapacityDTO.Detail;
import com.kitex.mom.wms.enums.menu.LiveStockMenuTypeEnum;
import com.kitex.mom.wms.mapper.WmsWarehouseCapacityMapper;
import com.kitex.mom.wms.mapper.WmsWarehouseMenuMapper;
import com.kitex.mom.wms.service.impl.WmsLiveStockReadAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * 按真实库存和显式换算监测容量；布局与容量配置通过共享版本互斥更新。
 */
@Service
@RequiredArgsConstructor
public class WmsWarehouseCapacityService {
	private final WmsWarehouseCapacityMapper mapper;
	private final WmsWarehouseCapacityCalculator calculator;
	private final RemoteWarehouseLocationService locations;
	private final RemoteWarehouseService warehouses;
	private final WmsLiveStockReadAccess access;
	private final PermissionService permissions;
	private final WmsWarehouseMenuMapper menus;
	private final ObjectMapper json;

	@Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
	public View load(String warehouseCode) {
		var scope = scope(warehouseCode);
		var master = master(scope);
		try {
			var policy = policy(scope);
			return new View(1, scope.tenant().toString(), scope.factory(), scope.warehouse(), policy, master,
					calculator.calculate(policy.model(), master, groups(scope, null)), LocalDateTime.now());
		} catch (DataAccessException unavailable) {
			throw new CheckedException("容量数据暂不可用，请确认仓库容量升级已完成");
		}
	}

	@Transactional(rollbackFor = Exception.class)
	public Saved save(SaveCommand command) {
		if (command == null || command.expectedVersion() < 0 || command.expectedVersion() == Integer.MAX_VALUE) {
			throw new CheckedException("容量配置版本无效，请刷新后重试");
		}
		var scope = scope(command.warehouseCode());
		if (!permissions.hasPermission("wms_wmsWarehouseMenu_add")) {
			throw new CheckedException("无仓库配置权限");
		}
		menus.lockConfiguration("WMS_WAREHOUSE_MENU:" + scope.tenant() + ":" + scope.factory());
		requireActiveWarehouse(scope);
		var master = master(scope);
		var model = calculator.normalize(command.model());
		var enabled = new HashSet<String>();
		for (var location : master) {
			if (location.enabled()) {
				enabled.add(location.locationCode());
			}
		}
		for (var limit : model.limits()) {
			if (!enabled.contains(limit.locationCode())) {
				throw new CheckedException("容量只能配置到当前仓库已启用的物理库位");
			}
		}
		try {
			String serialized = json.writeValueAsString(model);
			int changed = command.expectedVersion() == 0
					? mapper.insert(scope.tenant(), scope.factory(), scope.warehouse(), serialized, scope.actor())
					: mapper.update(scope.tenant(), scope.factory(), scope.warehouse(), serialized, command.expectedVersion(), scope.actor());
			if (changed != 1) {
				throw new CheckedException("仓库配置已更新，请刷新后重新调整容量");
			}
			return new Saved(1, scope.tenant().toString(), scope.factory(), scope.warehouse(), command.expectedVersion() + 1);
		} catch (JsonProcessingException invalid) {
			throw new CheckedException("容量配置无法保存，请核对后重试");
		} catch (DataAccessException unavailable) {
			throw new CheckedException("容量保存失败，请确认升级已完成并刷新核对");
		}
	}

	@Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
	public Detail details(String warehouseCode, String locationCode, int page, int size) {
		var scope = scope(warehouseCode);
		if (locationCode == null || (!locationCode.isEmpty() && !validCode(locationCode, 100)) || page < 1 || size < 1 || size > 100) {
			throw new CheckedException("请选择库位并使用有效分页条件");
		}
		try {
			var policy = policy(scope);
			var items = calculator.details(policy.model(), groups(scope, locationCode));
			int from = (int) Math.min(items.size(), ((long) page - 1) * size);
			return new Detail(1, scope.tenant().toString(), scope.factory(), scope.warehouse(), locationCode,
					policy.version(), policy.model().unit(), page, size, items.size(),
					List.copyOf(items.subList(from, Math.min(items.size(), from + size))), LocalDateTime.now());
		} catch (DataAccessException unavailable) {
			throw new CheckedException("占用明细暂不可用，请稍后刷新");
		}
	}

	private Policy policy(Scope scope) {
		var row = mapper.find(scope.tenant(), scope.factory(), scope.warehouse());
		if (row != null && row.version() < 1) {
			throw new CheckedException("仓库配置版本异常，请联系管理员核对");
		}
		if (row == null || row.capacityJson() == null) {
			return new Policy(new Model("M3", List.of(), List.of()), row == null ? 0 : row.version(), false);
		}
		try {
			return new Policy(calculator.normalize(json.readValue(row.capacityJson(), Model.class)), row.version(), true);
		} catch (JsonProcessingException invalid) {
			throw new CheckedException("历史容量配置无法读取，请联系管理员核对");
		}
	}

	private List<StockGroup> groups(Scope scope, String site) {
		var rows = mapper.groups(scope.tenant(), scope.factory(), scope.warehouse(), site);
		if (rows == null || rows.size() > 50000) {
			throw new CheckedException("库存分组过多或读取不完整，请先核对库存数据");
		}
		return rows;
	}

	private Scope scope(String warehouse) {
		Long tenant = TenantContextHolder.getTenantId();
		String factory = FactoryContextHolder.getFactoryCode();
		var actor = SecurityUtils.getUser();
		if (tenant == null || tenant <= 0 || !validCode(factory, 64) || !validCode(warehouse, 64)
				|| Boolean.TRUE.equals(TenantContextHolder.getTenantSkip()) || Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())
				|| actor == null || !Objects.equals(tenant, actor.getTenantId()) || actor.getUsername() == null || actor.getUsername().isBlank()) {
			throw new CheckedException("请在当前工厂中选择有权查看的仓库");
		}
		access.requireView(LiveStockMenuTypeEnum.STOCK_DETAIL, warehouse);
		return new Scope(tenant, factory, warehouse, actor.getUsername());
	}

	private List<Location> master(Scope scope) {
		var response = locations.getLayoutLocations(scope.warehouse());
		if (response == null || response.getCode() != 0 || response.getData() == null || response.getData().size() > 2000) {
			throw new CheckedException("库位目录读取失败或数量过多");
		}
		var result = new ArrayList<Location>();
		var seen = new HashSet<String>();
		for (var row : response.getData()) {
			if (row == null || !Objects.equals(scope.tenant(), row.getTenantId()) || !Objects.equals(scope.factory(), row.getFactoryCode())
					|| !Objects.equals(scope.warehouse(), row.getWarehouseCode()) || !validCode(row.getLocationCode(), 100)
					|| !seen.add(row.getLocationCode()) || row.getStatus() == null || row.getLocationLevel() == null
					|| row.getLocationLevel() < 1 || row.getLocationLevel() > 3) {
				throw new CheckedException("库位目录范围不一致或数据不完整");
			}
			if (row.getLocationLevel() == 3) {
				result.add(new Location(row.getLocationCode(),
						row.getLocationName() == null || row.getLocationName().isBlank() ? row.getLocationCode() : row.getLocationName(), row.getStatus()));
			}
		}
		result.sort(Comparator.comparing(Location::locationCode));
		return List.copyOf(result);
	}

	private void requireActiveWarehouse(Scope scope) {
		var response = warehouses.getMenuConfigurationWarehouses();
		if (response == null || response.getCode() != 0 || response.getData() == null) {
			throw new CheckedException("仓库状态读取失败");
		}
		int count = 0;
		for (var row : response.getData()) {
			if (row == null || !Objects.equals(scope.tenant(), row.getTenantId()) || !Objects.equals(scope.factory(), row.getFactoryCode())) {
				throw new CheckedException("仓库状态范围不一致");
			}
			if (scope.warehouse().equals(row.getWarehouseCode())) {
				count++;
				if (!Boolean.TRUE.equals(row.getStatus())) {
					throw new CheckedException("停用仓库不能修改容量配置");
				}
			}
		}
		if (count != 1) {
			throw new CheckedException("仓库不存在或无权配置");
		}
	}

	private boolean validCode(String value, int max) {
		return value != null && !value.isBlank() && value.equals(value.trim()) && value.length() <= max
				&& !value.contains("*") && !value.contains("?") && value.chars().noneMatch(Character::isISOControl);
	}

	private record Scope(Long tenant, String factory, String warehouse, String actor) {
	}
}
