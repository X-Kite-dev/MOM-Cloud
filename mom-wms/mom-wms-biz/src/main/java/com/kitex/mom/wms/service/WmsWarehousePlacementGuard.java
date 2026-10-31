package com.kitex.mom.wms.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mdm.entity.MdmWarehouseEntity;
import com.kitex.mom.mdm.entity.MdmWarehouseLocationEntity;
import com.kitex.mom.mdm.feign.RemoteWarehouseLocationService;
import com.kitex.mom.mdm.feign.RemoteWarehouseService;
import com.kitex.mom.wms.entity.WmsContainerBarcodeEntity;
import com.kitex.mom.wms.mapper.WmsContainerBarcodeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 普通收发的真实放置范围，不使用页面选择器或上次读取结果替代记账校验。
 */
@Component
@RequiredArgsConstructor
public class WmsWarehousePlacementGuard {

	private final RemoteWarehouseService warehouses;
	private final RemoteWarehouseLocationService locations;
	private final WmsContainerBarcodeMapper containers;

	public record Placement(String siteCode, String containerCode) {
	}

	public void check(String warehouseCode, List<Placement> placements) {
		Long tenant = TenantContextHolder.getTenantId();
		String factory = FactoryContextHolder.getFactoryCode();
		require(tenant != null && tenant > 0 && canonical(factory) && canonical(warehouseCode)
				&& !Boolean.TRUE.equals(TenantContextHolder.getTenantSkip())
				&& !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()), "请先选择实际租户、工厂和仓库");
		require(placements != null && !placements.isEmpty() && placements.size() <= 10000,
				"收发明细的库位信息不完整或超出单次范围");
		R<MdmWarehouseEntity> response = warehouses.getByCode(warehouseCode);
		MdmWarehouseEntity warehouse = response == null ? null : response.getData();
		require(response != null && response.isOk() && warehouse != null && Boolean.TRUE.equals(warehouse.getStatus())
						&& "0".equals(warehouse.getDelFlag()) && warehouseCode.equals(warehouse.getWarehouseCode())
						&& tenant.equals(warehouse.getTenantId()) && factory.equals(warehouse.getFactoryCode()),
				"仓库不可用或不属于当前组织，请重新选择");
		require(warehouse.getIsContainer() == null || Set.of(0, 1).contains(warehouse.getIsContainer()),
				"仓库容器配置无效，请核对基础设置");
		boolean required = Objects.equals(warehouse.getIsContainer(), 1);
		var sites = new HashSet<String>();
		var codes = new HashSet<String>();
		for (Placement placement : placements) {
			require(placement != null && canonical(placement.siteCode()), "请选择有效库位，编码不能包含前后空白");
			sites.add(placement.siteCode());
			String code = placement.containerCode();
			require(!required || canonical(code), "当前仓库要求使用容器，请选择或扫描容器");
			if (code != null && !code.isEmpty()) {
				require(canonical(code), "容器编码无效，请重新选择或扫描");
				codes.add(code);
			}
		}
		// 同批相同库位只读取一次；每次正式操作重新核对，不跨请求缓存配置。
		for (String code : sites) {
			R<MdmWarehouseLocationEntity> result = locations.getByCode(code);
			MdmWarehouseLocationEntity site = result == null ? null : result.getData();
			require(result != null && result.isOk() && site != null && Boolean.TRUE.equals(site.getStatus())
					&& "0".equals(site.getDelFlag()) && code.equals(site.getLocationCode())
					&& tenant.equals(site.getTenantId()) && factory.equals(site.getFactoryCode())
					&& warehouseCode.equals(site.getWarehouseCode()), "库位不可用或不属于当前仓库，请重新选择");
		}
		if (codes.isEmpty()) {
			return;
		}
		List<WmsContainerBarcodeEntity> found = containers.selectList(new LambdaQueryWrapper<WmsContainerBarcodeEntity>()
				.eq(WmsContainerBarcodeEntity::getTenantId, tenant)
				.eq(WmsContainerBarcodeEntity::getFactoryCode, factory)
				.in(WmsContainerBarcodeEntity::getCode, codes).last("LIMIT " + (codes.size() + 1)));
		require(found != null, "容器信息读取失败，请稍后重试");
		var actual = new HashMap<String, WmsContainerBarcodeEntity>();
		for (WmsContainerBarcodeEntity container : found) {
			require(container != null && codes.contains(container.getCode()) && "0".equals(container.getDelFlag())
							&& tenant.equals(container.getTenantId()) && factory.equals(container.getFactoryCode())
							&& warehouseCode.equals(container.getWarehouseCode())
							&& actual.putIfAbsent(container.getCode(), container) == null,
					"容器不属于当前仓库或存在重复记录，请重新核对");
		}
		require(actual.keySet().equals(codes), "所选容器不存在或已失效，请重新选择");
	}

	private boolean canonical(String code) {
		return code != null && !code.isBlank() && code.equals(code.trim()) && !code.matches(".*[\\p{Cntrl}].*");
	}

	private void require(boolean valid, String message) {
		if (!valid) {
			throw new CheckedException(message);
		}
	}
}
