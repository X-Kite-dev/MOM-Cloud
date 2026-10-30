package com.kitex.mom.wms.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mdm.entity.MdmWarehouseLocationEntity;
import com.kitex.mom.mdm.feign.RemoteWarehouseLocationService;
import com.kitex.mom.wms.entity.WmsLiveStockEntity;
import com.kitex.mom.wms.mapper.WmsLiveStockMapper;
import com.kitex.mom.wms.service.impl.WmsInventoryWarehousePermission;
import com.kitex.mom.wms.util.WmsStockOwnership;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import static com.kitex.mom.wms.service.impl.WmsInventoryPlanAccess.factory;
import static com.kitex.mom.wms.service.impl.WmsInventoryPlanAccess.require;

/** 只读盘点范围建议；保存计划时再次校验范围并重算账面数量。 */
@Service
@RequiredArgsConstructor
public class WmsInventoryScopeSuggestionService {
	private static final int MAX_STOCK_ROWS = 1000;

	private final WmsLiveStockMapper stocks;
	private final RemoteWarehouseLocationService locations;
	private final WmsInventoryWarehousePermission permission;
	private final WmsWarehouseBusinessGuard warehouseGuard;

	public Suggestion suggest(String warehouseCode, String siteCode) {
		return suggest(warehouseCode, siteCode, null, null);
	}

	public Suggestion suggest(String warehouseCode, String siteCode, String ruleCode, Integer thresholdDays) {
		return suggest(warehouseCode, siteCode, ruleCode, thresholdDays, true);
	}

	Suggestion suggestScheduled(String warehouseCode, String siteCode, String ruleCode, Integer thresholdDays) {
		return suggest(warehouseCode, siteCode, ruleCode, thresholdDays, false);
	}

	private Suggestion suggest(String warehouseCode, String siteCode, String ruleCode,
							   Integer thresholdDays, boolean requireUserPermission) {
		Rule rule = resolveRule(ruleCode, thresholdDays);
		Long tenant = TenantContextHolder.getTenantId();
		String currentFactory = factory();
		String warehouse = StrUtil.trim(warehouseCode);
		String site = StrUtil.trim(siteCode);
		require(StrUtil.isNotBlank(warehouse) && StrUtil.isNotBlank(site), "请选择盘点仓库和库位");
		if (requireUserPermission) {
			permission.require("add", warehouse);
		}
		require(warehouseGuard.check(warehouse, "other_inventory", true), "仓库不允许盘点");

		R<MdmWarehouseLocationEntity> response = locations.getByCode(site);
		require(response != null && response.isOk(), "读取盘点库位失败");
		MdmWarehouseLocationEntity location = response.getData();
		require(location != null && Objects.equals(tenant, location.getTenantId())
						&& (StrUtil.isBlank(location.getFactoryCode())
						|| Objects.equals(currentFactory, location.getFactoryCode()))
						&& Objects.equals(warehouse, location.getWarehouseCode())
						&& Objects.equals(site, location.getLocationCode())
						&& Boolean.TRUE.equals(location.getStatus())
						&& Objects.equals(3, location.getLocationLevel()),
				"盘点库位不存在、未启用或不属于当前组织仓库");

		List<WmsLiveStockEntity> observed = stocks.selectList(Wrappers.<WmsLiveStockEntity>lambdaQuery()
				.eq(WmsLiveStockEntity::getTenantId, tenant)
				.eq(WmsLiveStockEntity::getFactoryCode, currentFactory)
				.eq(WmsLiveStockEntity::getWarehouseCode, warehouse)
				.eq(WmsLiveStockEntity::getSiteCode, site)
				.gt(WmsLiveStockEntity::getMatNum, 0)
				.orderByAsc(WmsLiveStockEntity::getId)
				.last("LIMIT " + (MAX_STOCK_ROWS + 1)));
		require(observed != null && observed.size() <= MAX_STOCK_ROWS,
				"当前库位库存行超过1000条，请缩小盘点范围后手工选择");

		Map<String, List<WmsLiveStockEntity>> byMaterial = new TreeMap<>();
		for (WmsLiveStockEntity stock : observed) {
			require(stock != null && Objects.equals(tenant, stock.getTenantId())
							&& Objects.equals(currentFactory, stock.getFactoryCode())
							&& Objects.equals(warehouse, stock.getWarehouseCode())
							&& Objects.equals(site, stock.getSiteCode())
							&& StrUtil.isNotBlank(stock.getMatCode()) && StrUtil.isNotBlank(stock.getMatUnit())
							&& Objects.equals(stock.getMatCode(), stock.getMatCode().trim())
							&& stock.getMatNum() != null && Double.isFinite(stock.getMatNum())
							&& stock.getMatNum() > 0,
					"盘点建议中的库存范围或数量无效");
			if (StrUtil.isNotBlank(stock.getMatBatch())) {
				require(Objects.equals(stock.getMatBatch(), stock.getMatBatch().trim()),
						"库存批次含首尾空格，请先修正库存标识");
			}
			byMaterial.computeIfAbsent(stock.getMatCode(), ignored -> new ArrayList<>()).add(stock);
		}

		LocalDate today = LocalDate.now();
		LocalDateTime now = LocalDateTime.now();
		List<Row> rows = new ArrayList<>();
		for (var material : byMaterial.entrySet()) {
			List<WmsLiveStockEntity> materialStocks = material.getValue();
			String unit = materialStocks.get(0).getMatUnit();
			require(materialStocks.stream().allMatch(stock -> Objects.equals(unit, stock.getMatUnit())),
					"同一物料库位存在不同库存单位，请先核对");
			boolean allBatches = materialStocks.stream().anyMatch(stock -> StrUtil.isBlank(stock.getMatBatch()));
			Map<String, List<WmsLiveStockEntity>> byBatch = new TreeMap<>();
			for (WmsLiveStockEntity stock : materialStocks) {
				String batch = allBatches ? "" : stock.getMatBatch();
				byBatch.computeIfAbsent(batch, ignored -> new ArrayList<>()).add(stock);
			}
			for (var batch : byBatch.entrySet()) {
				if (batch.getValue().stream().noneMatch(stock -> matches(stock, rule, thresholdDays, today, now))) {
					continue;
				}
				BigDecimal quantity = batch.getValue().stream()
						.map(stock -> BigDecimal.valueOf(stock.getMatNum()))
						.reduce(BigDecimal.ZERO, BigDecimal::add);
				boolean external = batch.getValue().stream().anyMatch(stock -> !WmsStockOwnership.isCompanyOwned(stock));
				WmsLiveStockEntity first = batch.getValue().get(0);
				rows.add(new Row(material.getKey(), StrUtil.blankToDefault(first.getMatName(), material.getKey()),
						unit, batch.getKey().isEmpty() ? null : batch.getKey(), site, location.getLocationName(),
						quantity, external));
			}
		}
		return new Suggestion(warehouse, site, observed.size(), rule.name(), thresholdDays, List.copyOf(rows));
	}

	public static void validateRule(String ruleCode, Integer thresholdDays) {
		resolveRule(ruleCode, thresholdDays);
	}

	private static Rule resolveRule(String ruleCode, Integer thresholdDays) {
		String code = StrUtil.isBlank(ruleCode) ? Rule.ALL_POSITIVE.name() : ruleCode.trim();
		Rule rule;
		try {
			rule = Rule.valueOf(code);
		} catch (IllegalArgumentException exception) {
			throw new CheckedException("盘点建议规则无效");
		}
		boolean needsDays = rule == Rule.NEAR_EXPIRY || rule == Rule.LONG_STAY;
		require(needsDays ? thresholdDays != null && thresholdDays >= 1 && thresholdDays <= 3650
						: thresholdDays == null,
				"盘点建议天数必须与规则匹配，且范围为1至3650天");
		return rule;
	}

	private boolean matches(WmsLiveStockEntity stock, Rule rule, Integer days,
							LocalDate today, LocalDateTime now) {
		return switch (rule) {
			case ALL_POSITIVE -> true;
			case NEAR_EXPIRY -> stock.getMatValidEndDate() != null
					&& !stock.getMatValidEndDate().isAfter(today.plusDays(days));
			case LONG_STAY -> stock.getInTime() != null
					&& !stock.getInTime().isAfter(now.minusDays(days));
			case EXTERNAL_OWNER -> !WmsStockOwnership.isCompanyOwned(stock);
		};
	}

	private enum Rule {
		ALL_POSITIVE, NEAR_EXPIRY, LONG_STAY, EXTERNAL_OWNER
	}

	public record Suggestion(String warehouseCode, String siteCode, int stockRowCount,
							 String ruleCode, Integer thresholdDays, List<Row> rows) {
	}

	public record Row(String matCode, String matName, String matUnit, String matBatch,
					  String siteCode, String siteName, BigDecimal currentNumber,
					  boolean externalOwnerPresent) {
	}
}
