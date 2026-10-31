package com.kitex.mom.wms.service;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.wms.dto.WmsWarehouseCapacityDTO.*;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/** 仅显式SKU/库存单位系数可折算；未知量绝不当成空置容量。 */
@Component
public class WmsWarehouseCapacityCalculator {
	public Model normalize(Model model) {
		require(model != null && model.schemaVersion() == 1 && Set.of("M3", "KG", "SLOT").contains(model.unit())
				&& model.limits() != null && model.limits().size() <= 2000
				&& model.factors() != null && model.factors().size() <= 5000, "容量配置格式不完整");
		var limits = new TreeMap<String, Limit>();
		for (var row : model.limits()) {
			require(row != null && code(row.locationCode()), "请选择有效的物理库位");
			require(limits.putIfAbsent(row.locationCode(), new Limit(row.locationCode(), number(positive(row.capacity())))) == null,
					"同一库位的容量不能重复配置");
		}
		var factors = new TreeMap<String, Factor>();
		for (var row : model.factors()) {
			require(row != null && code(row.matCode()) && code(row.matUnit()), "物料及库存单位不能为空");
			require(factors.putIfAbsent(key(row.matCode(), row.matUnit()), new Factor(row.matCode(), row.matUnit(), number(positive(row.amount())))) == null,
					"同一物料和库存单位的换算不能重复配置");
		}
		return new Model(1, model.unit(), List.copyOf(limits.values()), List.copyOf(factors.values()));
	}

	public Result calculate(Model raw, List<Location> locations, List<StockGroup> groups) {
		var model = normalize(raw);
		var master = new TreeMap<String, Location>();
		for (var row : locations) {
			require(row != null && code(row.locationCode()) && master.putIfAbsent(row.locationCode(), row) == null,
					"物理库位目录不完整或重复");
		}
		var capacities = new HashMap<String, BigDecimal>();
		for (var row : model.limits()) {
			capacities.put(row.locationCode(), positive(row.capacity()));
		}
		var factors = factors(model);
		var bySite = new TreeMap<String, List<StockGroup>>();
		for (var row : groups) {
			require(row != null && row.stockRows() >= 0 && row.invalidRows() >= 0, "库存分组数据不完整");
			bySite.computeIfAbsent(row.siteCode() == null ? "" : row.siteCode(), ignored -> new ArrayList<>()).add(row);
		}
		var codes = new TreeSet<>(master.keySet());
		codes.addAll(bySite.keySet());
		codes.addAll(capacities.keySet());
		var result = new ArrayList<Position>();
		int active = 0;
		int configured = 0;
		int complete = 0;
		int overloaded = 0;
		long unresolved = 0;
		BigDecimal totalCapacity = BigDecimal.ZERO;
		BigDecimal totalOccupied = BigDecimal.ZERO;
		boolean fullyKnown = true;
		for (String site : codes) {
			var location = master.get(site);
			boolean enabled = location != null && location.enabled();
			var rows = bySite.getOrDefault(site, List.of());
			if (!enabled && rows.isEmpty() && !capacities.containsKey(site)) {
				continue;
			}
			BigDecimal known = BigDecimal.ZERO;
			long stockRows = 0;
			long missing = 0;
			boolean invalidQuantity = false;
			for (var group : rows) {
				var contribution = contribution(group, factors);
				stockRows += group.stockRows() + group.invalidRows();
				if (contribution.occupied() != null) {
					known = known.add(new BigDecimal(contribution.occupied()));
				}
				if (!"READY".equals(contribution.status())) {
					missing += group.stockRows() + group.invalidRows();
				}
				invalidQuantity |= "QUANTITY_INVALID".equals(contribution.status());
			}
			BigDecimal capacity = capacities.get(site);
			boolean knownLocation = enabled && capacity != null && missing == 0;
			String status = !enabled ? "LOCATION_UNAVAILABLE" : capacity == null ? "CAPACITY_MISSING"
					: invalidQuantity ? "QUANTITY_INVALID" : missing > 0 ? "CONVERSION_MISSING"
					: known.compareTo(capacity) > 0 ? "OVER_CAPACITY" : "READY";
			if (enabled) {
				active++;
				if (capacity != null) {
					configured++;
					totalCapacity = totalCapacity.add(capacity);
				}
				if (knownLocation) {
					complete++;
					totalOccupied = totalOccupied.add(known);
				} else {
					fullyKnown = false;
				}
			} else if (stockRows > 0) {
				fullyKnown = false;
			}
			if ("OVER_CAPACITY".equals(status)) {
				overloaded++;
			}
			unresolved += missing;
			result.add(new Position(site, location == null ? "未匹配库位" : location.locationName(), enabled,
					capacity == null ? null : number(capacity), number(known), knownLocation ? number(known) : null,
					knownLocation ? percentage(known, capacity) : null,
					knownLocation ? number(capacity.subtract(known).max(BigDecimal.ZERO)) : null,
					knownLocation ? number(known.subtract(capacity).max(BigDecimal.ZERO)) : null,
					stockRows, missing, status));
		}
		fullyKnown &= active > 0 && totalCapacity.signum() > 0;
		return new Result(new Summary(active, configured, complete, overloaded, unresolved,
				number(totalCapacity), fullyKnown ? number(totalOccupied) : null,
				fullyKnown ? percentage(totalOccupied, totalCapacity) : null), List.copyOf(result));
	}

	public List<Contribution> details(Model raw, List<StockGroup> groups) {
		var factors = factors(normalize(raw));
		return groups.stream().map(row -> contribution(row, factors)).toList();
	}

	private Map<String, BigDecimal> factors(Model model) {
		var result = new HashMap<String, BigDecimal>();
		for (var factor : model.factors()) {
			result.put(key(factor.matCode(), factor.matUnit()), positive(factor.amount()));
		}
		return result;
	}

	private Contribution contribution(StockGroup row, Map<String, BigDecimal> factors) {
		BigDecimal quantity = null;
		try {
			quantity = new BigDecimal(row.quantity());
			if (quantity.signum() < 0 || quantity.precision() > 40 || quantity.scale() > 16) {
				quantity = null;
			}
		} catch (NumberFormatException | NullPointerException invalid) {
			// 损坏或非有限库存不是负占用，也不能折算成零占用。
		}
		var factor = factors.get(key(row.matCode(), row.matUnit()));
		String status = quantity == null || row.invalidRows() > 0 ? "QUANTITY_INVALID"
				: factor == null ? "CONVERSION_MISSING" : "READY";
		return new Contribution(row.matCode(), row.matName(), row.matUnit(), quantity == null ? null : number(quantity),
				factor == null ? null : number(factor), "READY".equals(status) ? number(quantity.multiply(factor)) : null,
				row.stockRows(), row.invalidRows(), status);
	}

	private BigDecimal positive(String text) {
		require(text != null && text.matches("\\d{1,12}(\\.\\d{1,8})?"), "容量和换算系数须为正数，最多12位整数和8位小数");
		var value = new BigDecimal(text);
		require(value.signum() > 0, "容量和换算系数必须大于零");
		return value;
	}

	private String key(String material, String unit) {
		return String.valueOf(material) + '\0' + unit;
	}

	private boolean code(String value) {
		return value != null && !value.isBlank() && value.length() <= 100 && value.equals(value.trim())
				&& value.chars().noneMatch(Character::isISOControl) && !value.contains("*") && !value.contains("?");
	}

	private String percentage(BigDecimal occupied, BigDecimal capacity) {
		return number(occupied.multiply(BigDecimal.valueOf(100)).divide(capacity, 6, RoundingMode.HALF_UP));
	}

	private String number(BigDecimal value) {
		return value.stripTrailingZeros().toPlainString();
	}

	private void require(boolean condition, String message) {
		if (!condition) {
			throw new CheckedException(message);
		}
	}
}
