package com.kitex.mom.wms.service;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.wms.dto.WmsWarehouseTurnoverDTO.Balance;
import com.kitex.mom.wms.dto.WmsWarehouseTurnoverDTO.Issue;
import com.kitex.mom.wms.dto.WmsWarehouseTurnoverDTO.Day;
import com.kitex.mom.wms.dto.WmsWarehouseTurnoverDTO.Row;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 日末平均分母与毛出库量；按物料和库存单位分别计算，不混加。
 */
@Component
public class WmsWarehouseTurnoverCalculator {
	public List<Row> calculate(LocalDate from, LocalDate to, List<Balance> balances, List<Issue> issues) {
		long days = ChronoUnit.DAYS.between(from, to) + 1;
		if (days < 1 || days > 90) {
			throw new CheckedException("统计期间须为1至90个完整自然日");
		}
		var groups = new TreeMap<String, Map<LocalDate, Balance>>();
		var keys = new HashMap<String, String[]>();
		for (var balance : balances) {
			String key = key(balance.matCode(), balance.matUnit());
			keys.put(key, new String[]{balance.matCode(), balance.matUnit()});
			if (groups.computeIfAbsent(key, ignored -> new TreeMap<>()).putIfAbsent(balance.day(), balance) != null) {
				throw new CheckedException("历史日末库存存在重复记录");
			}
		}
		var outbound = new HashMap<String, Issue>();
		for (var issue : issues) {
			String key = key(issue.matCode(), issue.matUnit());
			keys.put(key, new String[]{issue.matCode(), issue.matUnit()});
			if (outbound.putIfAbsent(key, issue) != null) {
				throw new CheckedException("出库统计分组重复");
			}
		}
		if (keys.size() > 1000) {
			throw new CheckedException("物料单位分组超过1000，请指定物料编码后查询");
		}
		var result = new ArrayList<Row>();
		for (String key : new TreeSet<>(keys.keySet())) {
			var identity = keys.get(key);
			var values = groups.getOrDefault(key, Map.of());
			var series = new ArrayList<Day>();
			BigDecimal sum = BigDecimal.ZERO;
			boolean invalid = false;
			var opening = values.get(from.minusDays(1));
			invalid |= opening == null || opening.invalidRows() != 0 || number(opening.quantity()) == null;
			for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
				var balance = values.get(day);
				var quantity = balance == null ? null : number(balance.quantity());
				invalid |= quantity == null || balance.invalidRows() != 0;
				if (quantity != null) {
					sum = sum.add(quantity);
				}
				series.add(new Day(day, quantity == null ? null : text(quantity), balance == null ? 1 : balance.invalidRows()));
			}
			var issue = outbound.get(key);
			var quantity = issue == null ? BigDecimal.ZERO : number(issue.quantity());
			boolean invalidIssue = quantity == null || (issue != null && issue.invalidRows() != 0);
			String status = identity[0] == null || identity[0].isBlank() || identity[1] == null || identity[1].isBlank() ? "IDENTITY_INVALID"
					: invalid ? "HISTORY_INVALID" : invalidIssue ? "OUTBOUND_INVALID" : sum.signum() == 0 ? "ZERO_AVERAGE"
					: quantity.signum() == 0 ? "NO_OUTBOUND" : "READY";
			boolean ready = "READY".equals(status);
			var closing = values.get(to);
			result.add(new Row(identity[0], identity[1], opening == null ? null : opening.quantity(), closing == null ? null : closing.quantity(),
					invalid ? null : text(sum), invalid ? null : divide(sum, BigDecimal.valueOf(days), 12),
					invalidIssue ? null : text(quantity), ready ? divide(quantity.multiply(BigDecimal.valueOf(days)), sum, 6) : null,
					ready ? divide(sum, quantity, 6) : null, status, List.copyOf(series)));
		}
		return List.copyOf(result);
	}

	private String key(String material, String unit) {
		return (material == null ? "-:" : material.length() + ":" + material) + (unit == null ? "-:" : unit.length() + ":" + unit);
	}

	private BigDecimal number(String value) {
		try {
			var number = new BigDecimal(value);
			return number.signum() < 0 ? null : number;
		} catch (NumberFormatException | NullPointerException invalid) {
			return null;
		}
	}

	private String divide(BigDecimal value, BigDecimal divisor, int scale) {
		return text(value.divide(divisor, scale, RoundingMode.HALF_UP));
	}

	private String text(BigDecimal value) {
		return value.stripTrailingZeros().toPlainString();
	}
}
