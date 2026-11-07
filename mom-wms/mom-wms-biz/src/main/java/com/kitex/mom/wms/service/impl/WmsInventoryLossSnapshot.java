package com.kitex.mom.wms.service.impl;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.wms.dto.WmsInventoryLossStockRequest;
import com.kitex.mom.wms.entity.WmsInBillDetailEntity;
import com.kitex.mom.wms.entity.WmsInoutFlowEntity;
import com.kitex.mom.wms.entity.WmsInsideInventoryPlanDetailEntity;
import com.kitex.mom.wms.entity.WmsLiveStockEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static com.kitex.mom.wms.service.impl.WmsInventoryPlanAccess.*;

/**
 * V3只记录指定实物盘亏；V1/V2原协议仍由原codec处理。
 */
public final class WmsInventoryLossSnapshot {
	private WmsInventoryLossSnapshot() {
	}

	public record Physical(String matBatch, String supplierCode, String supplierName, String containerCode,
	                       String matBarcode,
	                       String matProduceDate, Integer matValidPeriod, String matValidEndDate, String stockOwnerType,
	                       String stockOwnerCode,
	                       String sourceBusinessNo, String matUnit) {
		public WmsInBillDetailEntity material() {
			return new WmsInBillDetailEntity().setMatBatch(matBatch).setSupplierCode(supplierCode)
					.setSupplierName(supplierName).setContainerCode(containerCode).setMatBarcode(matBarcode)
					.setMatProduceDate(date(matProduceDate)).setMatValidPeriod(matValidPeriod)
					.setMatValidEndDate(date(matValidEndDate))
					.setStockOwnerType(stockOwnerType).setStockOwnerCode(stockOwnerCode)
					.setSourceBusinessNo(sourceBusinessNo).setMatUnit(matUnit);
		}
	}

	public record Stock(String stockId, String quantity, String basisVersion, boolean serialManaged, Physical detail) {
		public WmsInventoryLossStockRequest request() {
			return new WmsInventoryLossStockRequest(stockId, quantity, basisVersion);
		}
	}

	public static Physical physical(WmsLiveStockEntity s) {
		return new Physical(s.getMatBatch(), s.getSupplierCode(), s.getSupplierName(), s.getContainerCode(),
				s.getMatBarcode(),
				s.getMatProduceDate() == null ? null : s.getMatProduceDate().toString(), s.getMatValidPeriod(),
				s.getMatValidEndDate() == null ? null : s.getMatValidEndDate().toString(),
				StrUtil.isBlank(s.getStockOwnerType()) ? "OWNED" : s.getStockOwnerType(),
				StrUtil.isBlank(s.getStockOwnerCode()) ? null : s.getStockOwnerCode(), s.getSourceBusinessNo(),
				s.getMatUnit());
	}

	public static long id(String value) {
		require(value != null && value.matches("[1-9][0-9]{0,18}") &&
				(value.length() < 19 || value.compareTo("9223372036854775807") <= 0), "所选实物身份无效，请重新选择");
		return Long.parseLong(value);
	}

	public static BigDecimal quantity(String value) {
		require(value != null && value.length() <= 350 && value.matches("(?:0|[1-9][0-9]{0,308})(?:\\.[0-9]{1,8})?"),
				"实物分配量须为正数，最多8位小数");
		BigDecimal n = new BigDecimal(value);
		require(n.signum() > 0 && Double.isFinite(n.doubleValue()) &&
				BigDecimal.valueOf(n.doubleValue()).compareTo(n) == 0, "实物分配量超出当前计量精度");
		return n;
	}

	public static String text(BigDecimal number) {
		return number.stripTrailingZeros().toPlainString();
	}

	public static List<WmsInventoryLossStockRequest> normalized(List<WmsInventoryLossStockRequest> requests) {
		require(requests != null && !requests.isEmpty() && requests.size() <= 100, "请选择1至100行实物并分配盘亏数量");
		var seen = new HashSet<Long>();
		var result = new ArrayList<WmsInventoryLossStockRequest>();
		for (var request : requests) {
			require(request != null, "所选实物记录缺失");
			long stock = id(request.stockId());
			require(seen.add(stock) && request.basisVersion() != null && request.basisVersion().matches("[0-9a-f]{64}"),
					"实物来源重复或依据不完整，请重新选择");
			result.add(new WmsInventoryLossStockRequest(Long.toString(stock), text(quantity(request.quantity())),
					request.basisVersion()));
		}
		result.sort(Comparator.comparingLong(x -> id(x.stockId())));
		return List.copyOf(result);
	}

	public static String freeze(String base, List<Stock> selected) {
		var map = new LinkedHashMap<String, Object>();
		map.put("schemaVersion", 3);
		map.put("base", WmsInventorySelectionSnapshot.parse(base));
		map.put("lossStocks", selected);
		return WmsInventorySelectionSnapshot.write(map);
	}

	public static WmsInventorySelectionSnapshot.Frozen read(WmsInsideInventoryPlanDetailEntity row, JsonNode root,
	                                                        boolean posting) {
		keys(root, Set.of("schemaVersion", "base", "lossStocks"));
		require(root.path("schemaVersion").isInt() && root.path("schemaVersion").intValue() == 3
						&& number(row.getCurrentNumber(), "账面数量").compareTo(number(row.getInventoryNum(), "实盘数量")) > 0,
				"指定实物仅适用于盘亏");
		var base = root.get("base");
		require(base.isObject() && base.path("schemaVersion").isInt() && base.path("schemaVersion").intValue() == 2,
				"指定实物原选择版本无效");
		var basic = WmsInventorySelectionSnapshot.read(row, base.toString(), posting);
		var array = root.get("lossStocks");
		require(array.isArray() && !array.isEmpty() && array.size() <= 100, "指定实物原件不完整");
		var selected = new ArrayList<Stock>();
		for (var item : array) {
			keys(item, Set.of("stockId", "quantity", "basisVersion", "serialManaged", "detail"));
			String stockId = string(item, "stockId");
			String quantity = string(item, "quantity");
			String version = string(item, "basisVersion");
			require(item.get("serialManaged").isBoolean(), "实物单件标记无效");
			var physical = parsePhysical(item.get("detail"), row.getMatUnit());
			boolean serial = item.get("serialManaged").booleanValue();
			require(!serial || quantity(quantity).compareTo(BigDecimal.ONE) == 0, "单件实物必须整件盘亏");
			selected.add(new Stock(stockId, text(quantity(quantity)), version, serial, physical));
		}
		normalized(selected.stream().map(Stock::request).toList());
		var total = selected.stream().map(s -> quantity(s.quantity())).reduce(BigDecimal.ZERO, BigDecimal::add);
		require(total.compareTo(
						number(row.getCurrentNumber(), "账面数量").subtract(number(row.getInventoryNum(), "实盘数量"))) == 0,
				"所选实物合计与原盘亏差异不一致");
		for (var stock : selected) {
			WmsInventorySelectionSnapshot.requireReceipt(row, basic, stock.detail().material(), false);
		}
		return new WmsInventorySelectionSnapshot.Frozen(3, false, basic.detail(), List.copyOf(selected));
	}

	public static boolean matches(WmsInsideInventoryPlanDetailEntity row, String original,
	                              WmsInBillDetailEntity selected, Boolean confirmation,
	                              List<WmsInventoryLossStockRequest> requests) {
		try {
			var root = WmsInventorySelectionSnapshot.parse(original);
			var frozen = read(row, root, false);
			var expected = normalized(frozen.lossStocks().stream().map(Stock::request).toList());
			return WmsInventorySelectionSnapshot.matches(row, root.get("base").toString(), selected, confirmation)
					&& normalized(requests).equals(expected);
		} catch (RuntimeException ex) {
			return false;
		}
	}

	public static Physical parsePhysical(JsonNode node, String unit) {
		keys(node, Set.of("matBatch", "supplierCode", "supplierName", "containerCode", "matBarcode", "matProduceDate",
				"matValidPeriod", "matValidEndDate", "stockOwnerType", "stockOwnerCode", "sourceBusinessNo",
				"matUnit"));
		var p = node.get("matValidPeriod");
		require(p.isNull() || p.isIntegralNumber() && p.canConvertToInt() && p.intValue() >= 0, "实物有效期无效");
		var detail = new Physical(nullable(node, "matBatch"), nullable(node, "supplierCode"),
				nullable(node, "supplierName"), nullable(node, "containerCode"), nullable(node, "matBarcode"),
				nullable(node, "matProduceDate"), p.isNull() ? null : p.intValue(), nullable(node, "matValidEndDate"),
				string(node, "stockOwnerType"), nullable(node, "stockOwnerCode"), nullable(node, "sourceBusinessNo"),
				string(node, "matUnit"));
		require("OWNED".equals(detail.stockOwnerType()) && detail.stockOwnerCode() == null &&
				Objects.equals(unit, detail.matUnit()), "指定实物货权或单位不一致");
		var produced = date(detail.matProduceDate());
		var expires = date(detail.matValidEndDate());
		require(produced == null || expires == null || !expires.isBefore(produced), "实物效期先后不一致");
		return detail;
	}

	public static void requireOutDetails(WmsInventorySelectionSnapshot.Frozen frozen,
	                                     List<com.kitex.mom.wms.entity.WmsOutBillDetailEntity> items) {
		if (frozen.lossStocks().isEmpty()) {
			return;
		}
		var expected = new HashMap<Long, Stock>();
		frozen.lossStocks().forEach(s -> expected.put(id(s.stockId()), s));
		var seen = new HashSet<Long>();
		for (var item : items) {
			var selected = expected.get(item.getSourceStockId());
			require(selected != null && seen.add(item.getSourceStockId()) &&
							quantity(selected.quantity()).compareTo(number(item.getMatNum(), "指定实物单据数量")) == 0,
					"盘亏单实物来源或分配量与批准内容不一致");
		}
		require(seen.equals(expected.keySet()), "盘亏单缺少指定实物来源");
	}

	public static void requireFlows(WmsInventorySelectionSnapshot.Frozen frozen, List<WmsInoutFlowEntity> flows) {
		if (frozen.lossStocks().isEmpty()) {
			return;
		}
		var expected = new HashMap<Long, Stock>();
		frozen.lossStocks().forEach(s -> expected.put(id(s.stockId()), s));
		var totals = new HashMap<Long, BigDecimal>();
		for (var flow : flows) {
			var stock = expected.get(flow.getSourceStockId());
			require(stock != null, "实际扣减了未经批准的实物");
			var actual = new Physical(flow.getMatBatch(), flow.getSupplierCode(), flow.getSupplierName(),
					flow.getContainerCode(), flow.getMatBarcode(),
					flow.getMatProduceDate() == null ? null : flow.getMatProduceDate().toString(),
					flow.getMatValidPeriod(),
					flow.getMatValidEndDate() == null ? null : flow.getMatValidEndDate().toString(),
					StrUtil.isBlank(flow.getStockOwnerType()) ? "OWNED" : flow.getStockOwnerType(),
					StrUtil.isBlank(flow.getStockOwnerCode()) ? null : flow.getStockOwnerCode(),
					flow.getSourceBusinessNo(), flow.getMatUnit());
			require(stock.detail().equals(actual) &&
							stock.serialManaged() == Boolean.TRUE.equals(flow.getSerialManaged()),
					"实际扣减实物属性与原批准内容不一致");
			totals.merge(flow.getSourceStockId(), number(flow.getMatNum(), "实际扣减量"), BigDecimal::add);
		}
		require(expected.keySet().equals(totals.keySet()) && expected.entrySet().stream()
						.allMatch(e -> quantity(e.getValue().quantity()).compareTo(totals.get(e.getKey())) == 0),
				"逐实物实际扣减量与原分配不守恒");
	}

	private static LocalDate date(String value) {
		if (value == null) {
			return null;
		}
		try {
			var d = LocalDate.parse(value);
			require(d.getYear() >= 1 && d.getYear() <= 9999, "实物日期超出支持范围");
			return d;
		} catch (java.time.DateTimeException ex) {
			throw new CheckedException("实物日期无效");
		}
	}

	private static void keys(JsonNode node, Set<String> keys) {
		require(node != null && node.isObject(), "实物属性格式无效");
		var actual = new HashSet<String>();
		node.fieldNames().forEachRemaining(actual::add);
		require(actual.equals(keys), "实物属性缺失或版本不支持");
	}

	private static String nullable(JsonNode node, String key) {
		var v = node.get(key);
		require(v != null && (v.isNull() || v.isTextual() && v.textValue().length() <= 1000), "实物属性类型无效");
		return v.isNull() ? null : v.textValue();
	}

	private static String string(JsonNode node, String key) {
		var value = nullable(node, key);
		require(value != null, "实物属性缺失");
		return value;
	}
}
