package com.kitex.mom.wms.service;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.wms.entity.WmsInNoticeEntity;
import com.kitex.mom.wms.enums.menu.ReturnReceiptActionEnum;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 退料能力使用原单实际仓库授权；永久回执查询不依赖仓库仍可写入。 */
@Service
@RequiredArgsConstructor
public class WmsReturnReceiptAccessService {
	private final PermissionService permissions;
	private final WmsWarehouseBusinessGuard businessGuard;

	public void requirePermission(String warehouse, ReturnReceiptActionEnum action) {
		requireScope(warehouse);
		if (!permitted(warehouse, action)) {
			throw new AccessDeniedException("当前账号无此仓库的" + action.getLabel() + "权限");
		}
	}

	public void requireNotice(WmsInNoticeEntity notice, ReturnReceiptActionEnum action) {
		validateNotice(notice);
		requirePermission(notice.getWarehouseCode(), action);
	}

	public void requireEnabled(WmsInNoticeEntity notice, ReturnReceiptActionEnum action) {
		requireNotice(notice, action);
		if (!businessGuard.returnReceiptActions(notice.getWarehouseCode()).contains(action)) {
			throw new CheckedException("当前仓库未启用退料" + action.getLabel());
		}
	}

	/** 只返回当前来源实际可用的能力，未授权的普通收货员无需请求专用详情。 */
	public Map<String, Boolean> capabilities(WmsInNoticeEntity notice) {
		validateNotice(notice);
		Map<String, Boolean> result = new LinkedHashMap<>();
		for (ReturnReceiptActionEnum action : ReturnReceiptActionEnum.values()) {
			result.put(action.getCode(), permitted(notice.getWarehouseCode(), action));
		}
		if (result.containsValue(true)) {
			Set<ReturnReceiptActionEnum> enabled;
			try {
				enabled = businessGuard.returnReceiptActions(notice.getWarehouseCode());
			} catch (CheckedException exception) {
				// 停用仓库仍可核对已封存回执，但不开放新的收货、处置或续跑。
				enabled = Set.of();
			}
			for (ReturnReceiptActionEnum action : ReturnReceiptActionEnum.values()) {
				if (!enabled.contains(action)) result.put(action.getCode(), false);
			}
		}
		return result;
	}

	private boolean permitted(String warehouse, ReturnReceiptActionEnum action) {
		return permissions.hasPermission(action.permission(warehouse))
				|| (permissions.hasPermission(action.permission()) && (
						permissions.hasPermission("wms_notice_view_return_" + warehouse)
						|| permissions.hasPermission("wms_notice_inout_return_" + warehouse)
						|| permissions.hasPermission("wms_notice_view_return")
						|| permissions.hasPermission("wms_notice_inout_return")));
	}

	private void validateNotice(WmsInNoticeEntity notice) {
		if (notice == null) throw new AccessDeniedException("原生产退料通知不存在或不可访问");
		requireScope(notice.getWarehouseCode());
		if (!Objects.equals(TenantContextHolder.getTenantId(), notice.getTenantId())
				|| !Objects.equals(FactoryContextHolder.getFactoryCode(), notice.getFactoryCode())
				|| !"return".equals(notice.getBillType())
				|| !"mom_mes.mes_warehouse_stock_feed_judge".equals(notice.getDocumentTable())) {
			throw new AccessDeniedException("当前租户、工厂和仓库下不存在原生产退料通知");
		}
	}

	private void requireScope(String warehouse) {
		Long tenant = TenantContextHolder.getTenantId();
		String factory = FactoryContextHolder.getFactoryCode();
		var actor = SecurityUtils.getUser();
		if (tenant == null || tenant <= 0 || Boolean.TRUE.equals(TenantContextHolder.getTenantSkip())
				|| Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()) || factory == null || factory.isBlank()
				|| actor == null || actor.getUsername() == null || actor.getUsername().isBlank()
				|| warehouse == null || warehouse.isBlank() || !warehouse.equals(warehouse.trim())
				|| warehouse.length() > 64 || warehouse.contains("*") || warehouse.contains("?")) {
			throw new AccessDeniedException("退料操作需要实际租户、工厂、仓库和登录账号");
		}
	}
}
