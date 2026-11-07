package com.kitex.mom.wms.service;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.common.security.component.PermissionService;
import com.kitex.mom.wms.dto.WmsWarehouseTurnoverDTO.Context;
import com.kitex.mom.wms.dto.WmsWarehouseTurnoverDTO.View;
import com.kitex.mom.wms.enums.menu.LiveStockMenuTypeEnum;
import com.kitex.mom.wms.repository.WmsWarehouseTurnoverRepository;
import com.kitex.mom.wms.service.impl.WmsLiveStockReadAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Set;

/**
 * 完整自然日的数量周转；缺历史和零分母不生成伪指标。
 */
@Service
@RequiredArgsConstructor
public class WmsWarehouseTurnoverService {
	private final WmsWarehouseTurnoverRepository repository;
	private final WmsWarehouseTurnoverCalculator calculator;
	private final WmsLiveStockReadAccess access;
	private final PermissionService permission;

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public Context context(String warehouse) {
		var scope = scope(warehouse);
		return context(scope);
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public View query(String warehouse, LocalDate from, LocalDate to, String basis, String material) {
		var scope = scope(warehouse);
		if (from == null || to == null || from.isAfter(to) || ChronoUnit.DAYS.between(from, to) >= 90
				|| basis == null || !Set.of("SALES", "PRODUCTION", "BOTH").contains(basis)
				|| (material != null && !material.isEmpty() && !code(material, 100))) {
			throw new CheckedException("请选择有效的统计日期、出库口径和物料编码");
		}
		var context = context(scope);
		if (from.isBefore(context.firstDate()) || to.isAfter(context.lastDate())) {
			throw new CheckedException("仅可查询历史采集启用后的完整自然日");
		}
		String filter = material == null || material.isEmpty() ? null : material;
		try {
			var rows = calculator.calculate(from, to, repository.balances(scope.tenant(), scope.factory(), warehouse, from, to, filter),
					repository.issues(scope.tenant(), scope.factory(), warehouse, from, to, basis, filter));
			return new View(1, scope.tenant().toString(), scope.factory(), warehouse, from, to, basis,
					filter == null ? "" : filter, (int) ChronoUnit.DAYS.between(from, to) + 1, rows, context.observedAt());
		} catch (DataAccessException unavailable) {
			throw new CheckedException("历史库存统计暂不可用，请稍后重试");
		}
	}

	private Context context(Scope scope) {
		try {
			var origin = repository.origin();
			var now = repository.now();
			if (origin == null || now == null || origin.isAfter(now)) {
				throw new CheckedException("历史库存采集状态异常");
			}
			var first = origin.toLocalDate().plusDays(1);
			var last = now.toLocalDate().minusDays(1);
			return new Context(1, scope.tenant().toString(), scope.factory(), scope.warehouse(), first, last, !first.isAfter(last), now);
		} catch (DataAccessException unavailable) {
			throw new CheckedException("历史库存采集尚未就绪，请完成数据升级后重试");
		}
	}

	private Scope scope(String warehouse) {
		var tenant = TenantContextHolder.getTenantId();
		var factory = FactoryContextHolder.getFactoryCode();
		var user = SecurityUtils.getUser();
		if (tenant == null || tenant <= 0 || !code(factory, 64) || !code(warehouse, 64)
				|| Boolean.TRUE.equals(TenantContextHolder.getTenantSkip()) || Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())
				|| user == null || !Objects.equals(tenant, user.getTenantId()) || user.getUsername() == null || user.getUsername().isBlank()) {
			throw new CheckedException("请在当前工厂选择有权查看的仓库");
		}
		access.requireView(LiveStockMenuTypeEnum.STOCK_DETAIL, warehouse);
		if (!permission.hasPermission("wms_wmsInoutFlow_view") && !permission.hasPermission("wms_wmsInoutFlow_view_" + warehouse)) {
			throw new CheckedException("无该仓库出入库流水查看权限");
		}
		return new Scope(tenant, factory, warehouse);
	}

	private boolean code(String value, int max) {
		return value != null && !value.isBlank() && value.equals(value.trim()) && value.length() <= max
				&& !value.contains("*") && !value.contains("?") && value.chars().noneMatch(Character::isISOControl);
	}

	private record Scope(Long tenant, String factory, String warehouse) {
	}
}
