package com.kitex.mom.wms.service;

import cn.hutool.core.util.StrUtil;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.feign.RemoteWarehouseService;
import com.kitex.mom.wms.mapper.WmsInventoryCountEventMapper;
import com.kitex.mom.wms.mapper.WmsInventoryCountTemplateMapper;
import com.kitex.mom.wms.mapper.WmsWarehouseMenuMapper;
import com.kitex.mom.wms.service.impl.WmsInventoryWarehousePermission;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

import static com.kitex.mom.wms.service.impl.WmsInventoryPlanAccess.factory;
import static com.kitex.mom.wms.service.impl.WmsInventoryPlanAccess.require;

/**
 * 仓库模板的事件订阅；启用边界由事件共用序列分配，不依赖前端时钟。
 */
@Service
@RequiredArgsConstructor
public class WmsInventoryCountEventConfigService {
	private final WmsInventoryCountEventMapper events;
	private final WmsInventoryCountTemplateMapper templates;
	private final WmsWarehouseMenuMapper menus;
	private final WmsInventoryWarehousePermission permission;
	private final PermissionService permissions;
	private final RemoteWarehouseService warehouses;

	public Policy get(String warehouse, String code) {
		var scope = scope(warehouse, code);
		requireTemplate(scope);
		var row = events.policy(scope.tenant(), scope.factory(), scope.warehouse(), scope.code());
		return row == null ? new Policy(0, false, false, false, "0") : read(row);
	}

	@Transactional(rollbackFor = Exception.class)
	public Policy save(Command command) {
		require(command != null, "请填写事件触发规则");
		var s = scope(command.warehouseCode(), command.templateCode());
		require(permissions.hasPermission("wms_wmsWarehouseMenu_add"), "无仓库配置权限");
		require(command.expectedVersion() >= 0 && (!command.enabled() || command.expiration() || command.slowMoving()),
				"启用时至少选择一种预警");
		menus.lockConfiguration("WMS_WAREHOUSE_MENU:" + s.tenant() + ":" + s.factory());
		var template = requireTemplate(s);
		if (command.enabled()) {
			require(template.enabled(), "请先启用盘点模板");
			var response = warehouses.getByCode(s.warehouse());
			require(response != null && response.isOk(), "仓库信息读取失败");
			var w = response.getData();
			require(w != null && Objects.equals(w.getTenantId(), s.tenant()) && s.factory().equals(w.getFactoryCode())
							&& s.warehouse().equals(w.getWarehouseCode()) && Boolean.TRUE.equals(w.getStatus()),
					"仅支持当前工厂已启用仓库");
		}
		var previous = events.lockPolicy(s.tenant(), s.factory(), s.warehouse(), s.code());
		int version = previous == null ? 0 : read(previous).version();
		require(version == command.expectedVersion() && version < Integer.MAX_VALUE, "事件规则已更新，请刷新核对");
		boolean changed = previous == null || previous.expiration() != command.expiration() ||
				previous.slowMoving() != command.slowMoving();
		Long start = previous == null ? 0L : previous.startEventId();
		if (command.enabled() && (changed || start == 0)) {
			start = events.boundary();
		}
		// 修改类型时即使暂停，也切换边界；随后恢复不能补做旧类型的历史预警。
		if (!command.enabled() && changed && previous != null && previous.startEventId() > 0) {
			start = events.boundary();
		}
		require(start != null && start >= 0 && (!command.enabled() || start > 0), "事件登记边界读取失败");
		var user = SecurityUtils.getUser();
		require(user != null && StrUtil.isNotBlank(user.getUsername()), "无法识别当前操作人");
		require(events.savePolicy(s.tenant(), s.factory(), s.warehouse(), s.code(), command.enabled(),
				command.expiration(),
				command.slowMoving(), start, user.getUsername(), version) == 1, "事件规则保存结果未确认，请刷新核对");
		return new Policy(version + 1, command.enabled(), command.expiration(), command.slowMoving(),
				String.valueOf(start));
	}

	public List<History> history(String warehouse, String code) {
		var s = scope(warehouse, code);
		requireTemplate(s);
		var rows = events.history(s.tenant(), s.factory(), s.warehouse(), s.code());
		require(rows != null && rows.size() <= 20, "事件处理记录读取失败");
		return rows.stream().map(row -> {
			require(row != null && row.eventId() != null && row.eventId() > 0 && row.attempts() > 0 &&
							row.policyVersion() > 0
							&& List.of("CREATED", "SKIPPED", "FAILED").contains(row.status()) &&
							StrUtil.isNotBlank(row.warningNo())
							&& StrUtil.isNotBlank(row.operatedTime()) && StrUtil.isNotBlank(row.operatorCode())
							&& (("CREATED".equals(row.status()) && row.planId() != null && row.planId() > 0 &&
							StrUtil.isNotBlank(row.planNo()))
							|| (!"CREATED".equals(row.status()) && row.planId() == null && row.planNo() == null)),
					"事件处理记录不完整");
			return new History(String.valueOf(row.eventId()), row.warningNo(), row.eventType(), row.matCode(),
					row.siteCode(), row.matBatch(),
					row.status(), row.planId() == null ? null : String.valueOf(row.planId()), row.planNo(),
					row.attempts(), row.message(),
					row.policyVersion(), row.operatorCode(), row.operatedTime());
		}).toList();
	}

	Scope scope(String warehouse, String code) {
		require(StrUtil.isNotBlank(warehouse) && warehouse.equals(warehouse.trim()) && warehouse.length() <= 100
				&& code != null && code.matches("[A-Z][A-Z0-9_]{1,31}"), "请选择当前仓库的盘点模板");
		permission.require("add", warehouse);
		Long tenant = TenantContextHolder.getTenantId();
		require(tenant != null && tenant > 0 && !Boolean.TRUE.equals(TenantContextHolder.getTenantSkip()),
				"当前组织上下文无效");
		return new Scope(tenant, factory(), warehouse, code);
	}

	private WmsInventoryCountTemplateMapper.TemplateRow requireTemplate(Scope s) {
		var t = templates.find(s.tenant(), s.factory(), s.code());
		require(t != null && s.warehouse().equals(t.warehouseCode()), "盘点模板不属于当前仓库");
		return t;
	}

	private Policy read(WmsInventoryCountEventMapper.PolicyRow row) {
		require(row.version() > 0 && row.startEventId() != null && row.startEventId() >= 0
						&& (!row.enabled() || (row.startEventId() > 0 && (row.expiration() || row.slowMoving()))),
				"事件规则结构无效");
		return new Policy(row.version(), row.enabled(), row.expiration(), row.slowMoving(),
				String.valueOf(row.startEventId()));
	}

	record Scope(Long tenant, String factory, String warehouse, String code) {
	}

	public record Policy(int version, boolean enabled, boolean expiration, boolean slowMoving, String startEventId) {
	}

	public record Command(String warehouseCode, String templateCode, boolean enabled, boolean expiration,
	                      boolean slowMoving, int expectedVersion) {
	}

	public record History(String eventId, String warningNo, String eventType, String matCode, String siteCode,
	                      String matBatch, String status, String planId, String planNo, int attempts, String message,
	                      int policyVersion, String operatorCode, String operatedTime) {
	}
}
