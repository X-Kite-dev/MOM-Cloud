package com.kitex.mom.wms.service;

import cn.hutool.core.util.StrUtil;
import com.kitex.admin.api.feign.RemoteMenuService;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mdm.entity.MdmWarehouseEntity;
import com.kitex.mom.mdm.feign.RemoteWarehouseService;
import com.kitex.mom.wms.entity.WmsWarehouseMenuEntity;
import com.kitex.mom.wms.mapper.WmsWarehouseLifecycleMapper;
import com.kitex.mom.wms.mapper.WmsWarehouseMenuMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 业务历史仅查询，永久删除时清理当前租户/工厂的仓库配置与生成预览。
 */
@Service
@RequiredArgsConstructor
public class WmsWarehouseLifecycleService {

	private final WmsWarehouseLifecycleMapper mapper;
	private final WmsWarehouseMenuMapper menuMapper;
	private final RemoteWarehouseService warehouses;
	private final RemoteMenuService menus;

	public List<String> references(String warehouseCode) {
		return references(warehouseCode, true);
	}

	public List<String> businessReferences(String warehouseCode) {
		return references(warehouseCode, false);
	}

	private List<String> references(String warehouseCode, boolean includeConfiguration) {
		requireScope(warehouseCode);
		List<String> references = mapper.findReferences(TenantContextHolder.getTenantId(),
				FactoryContextHolder.getFactoryCode(), warehouseCode, includeConfiguration);
		if (references == null || references.stream().anyMatch(StrUtil::isBlank)) {
			throw new CheckedException("仓库依赖查询结果不确定，禁止删除");
		}
		return references;
	}

	@Transactional(rollbackFor = Exception.class)
	public boolean cleanup(String warehouseCode) {
		requireScope(warehouseCode);
		Long tenant = TenantContextHolder.getTenantId();
		String factory = FactoryContextHolder.getFactoryCode();
		// 与配置保存/发布相同的组织事务锁；包含尚无配置的仓库。
		menuMapper.lockConfiguration("WMS_WAREHOUSE_MENU:" + tenant + ":" + factory);
		requireDisabledWarehouse(warehouseCode, tenant, factory);
		if (!businessReferences(warehouseCode).isEmpty()) {
			throw new CheckedException("仓库存在WMS业务或历史记录，不能清理删除");
		}
		List<WmsWarehouseMenuEntity> preview = mapper.findPreviewForCleanup(tenant, factory);
		if (preview == null) {
			throw new CheckedException("仓库菜单预览查询结果不确定，禁止清理");
		}
		Set<Long> ids = new HashSet<>();
		String root = "/wms/warehouseManege/" + factory + "/" + warehouseCode;
		for (WmsWarehouseMenuEntity row : preview) {
			if (row == null || row.getId() == null) {
				throw new CheckedException("仓库菜单预览结构不确定，禁止清理");
			}
			if (root.equals(row.getPath())) {
				ids.add(row.getId());
			}
		}
		boolean expanded;
		do {
			expanded = false;
			for (WmsWarehouseMenuEntity row : preview) {
				if (ids.contains(row.getParentId())) {
					expanded |= ids.add(row.getId());
				}
			}
		} while (expanded);
		if (!ids.isEmpty() && mapper.deletePreview(tenant, factory, ids) != ids.size()) {
			throw new CheckedException("清理仓库菜单预览失败");
		}
		Long configurationCount = mapper.countBusinessConfiguration(tenant, factory, warehouseCode);
		if (configurationCount == null
				|| mapper.deleteBusinessConfiguration(tenant, factory, warehouseCode) != configurationCount
						.longValue()) {
			throw new CheckedException("清理仓库业务配置失败");
		}
		int organizationPolicies = mapper.disableOrganizationPolicy(tenant, factory, warehouseCode);
		if (organizationPolicies < 0 || organizationPolicies > 1) throw new CheckedException("库存组织停用结果不确定，禁止删除仓库");
		Long policyCount = mapper.countDashboardPolicy(tenant, factory, warehouseCode);
		if (policyCount == null || mapper.deleteDashboardPolicy(tenant, factory, warehouseCode) != policyCount) {
			throw new CheckedException("清理仓库看板配置失败");
		}
		Long locationMapCount = mapper.countLocationMap(tenant, factory, warehouseCode);
		if (locationMapCount == null || mapper.deleteLocationMap(tenant, factory, warehouseCode) != locationMapCount) {
			throw new CheckedException("清理仓库库位图配置失败");
		}
		Long countPolicyCount = mapper.countInventoryScopePolicy(tenant, factory, warehouseCode);
		if (countPolicyCount == null
				|| mapper.deleteInventoryScopePolicy(tenant, factory, warehouseCode) != countPolicyCount) {
			throw new CheckedException("清理仓库盘点规则失败");
		}
		Long templateCount = mapper.countInventoryCountTemplate(tenant, factory, warehouseCode);
		if (templateCount == null
				|| mapper.deleteInventoryCountTemplate(tenant, factory, warehouseCode) != templateCount) {
			throw new CheckedException("清理仓库盘点模板失败");
		}
		R<Boolean> response;
		try {
			response = menus.removeWarehouseMenus(warehouseCode);
		} catch (RuntimeException exception) {
			throw new CheckedException("系统菜单清理失败，请重试删除");
		}
		// 仅本地事务能回滚；远程已提交后丢失回执的情况通过幂等重试恢复。
		if (response == null || response.getCode() != 0 || !Boolean.TRUE.equals(response.getData())) {
			throw new CheckedException("系统菜单清理失败或结果不确定，请重试删除");
		}
		return true;
	}

	private void requireDisabledWarehouse(String warehouseCode, Long tenant, String factory) {
		R<List<MdmWarehouseEntity>> response = warehouses.getMenuConfigurationWarehouses();
		if (response == null || response.getCode() != 0 || response.getData() == null) {
			throw new CheckedException("仓库状态查询失败，禁止清理");
		}
		Set<String> seen = new HashSet<>();
		MdmWarehouseEntity selected = null;
		for (MdmWarehouseEntity warehouse : response.getData()) {
			if (warehouse == null || StrUtil.isBlank(warehouse.getWarehouseCode()) || warehouse.getStatus() == null
					|| !seen.add(warehouse.getWarehouseCode()) || !Objects.equals(tenant, warehouse.getTenantId())
					|| !Objects.equals(factory, warehouse.getFactoryCode())) {
				throw new CheckedException("仓库状态重复、缺失或组织范围不一致，禁止清理");
			}
			if (warehouseCode.equals(warehouse.getWarehouseCode())) {
				selected = warehouse;
			}
		}
		if (selected == null || !Boolean.FALSE.equals(selected.getStatus())) {
			throw new CheckedException("仅允许清理当前租户/工厂已停用的实际仓库");
		}
	}

	private void requireScope(String warehouseCode) {
		if (TenantContextHolder.getTenantId() == null || Boolean.TRUE.equals(TenantContextHolder.getTenantSkip())
				|| StrUtil.isBlank(FactoryContextHolder.getFactoryCode())
				|| Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()) || StrUtil.isBlank(warehouseCode)) {
			throw new CheckedException("仓库生命周期操作必须提供仓库并处于精确租户/工厂上下文");
		}
	}
}
