package com.kitex.mom.wms.service.impl;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.wms.entity.WmsInBillDetailEntity;
import com.kitex.mom.wms.entity.WmsInsideInventoryPlanDetailEntity;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.kitex.mom.wms.service.impl.WmsInventoryPlanAccess.number;
import static com.kitex.mom.wms.service.impl.WmsInventoryPlanAccess.require;

/**
 * 仅描述自有库存损益选定属性；不产生外部货权确认或结算事实。
 */
public final class WmsInventorySelectionSnapshot {
	private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
			.enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
	private static final List<String> PHYSICAL = List.of("matBatch", "supplierCode", "supplierName", "containerCode",
			"matBarcode", "matProduceDate", "matValidPeriod", "matValidEndDate");
	private static final Set<String> V2 = Set.of("matBatch", "supplierCode", "supplierName", "containerCode",
			"matBarcode", "matProduceDate", "matValidPeriod", "matValidEndDate", "schemaVersion", "stockOwnerType",
			"stockOwnerCode", "sourceBusinessNo", "matUnit", "ownershipConfirmed");

	private WmsInventorySelectionSnapshot() {
	}

	public static String capture(WmsInsideInventoryPlanDetailEntity row, WmsInBillDetailEntity selected,
	                             Boolean confirmation) {
		require(row != null && selected != null && StrUtil.isNotBlank(row.getMatUnit()),
				"盘点物料属性或原计量单位缺失");
		require("OWNED".equals(selected.getStockOwnerType()) && StrUtil.isBlank(selected.getStockOwnerCode()),
				"本流程仅办理自有库存损益；客供或寄售请按原货主核对处置");
		require(row.getMatUnit().equals(selected.getMatUnit()), "损益计量单位与原盘点不一致，请重新核对");
		boolean gain = gain(row);
		require(gain || !Boolean.TRUE.equals(confirmation), "盘亏无需新增货权确认");
		require(!gain || Boolean.TRUE.equals(confirmation), "请先确认盘盈新增数量为本企业自有库存");
		String source = source(selected.getSourceBusinessNo());
		require(gain || source == null, "盘亏来源由实际出库库存确定，不能手填或替换");
		var map = physical(selected);
		validatePhysical(row, selected);
		map.put("schemaVersion", 2);
		map.put("stockOwnerType", "OWNED");
		map.put("stockOwnerCode", null);
		map.put("sourceBusinessNo", source);
		map.put("matUnit", row.getMatUnit());
		map.put("ownershipConfirmed", gain);
		return write(map);
	}

	public static boolean matches(WmsInsideInventoryPlanDetailEntity row, String original,
	                              WmsInBillDetailEntity selected, Boolean confirmation) {
		return matches(row, original, selected, confirmation, null);
	}

	public static boolean matches(WmsInsideInventoryPlanDetailEntity row, String original,
	                              WmsInBillDetailEntity selected, Boolean confirmation,
	                              List<com.kitex.mom.wms.dto.WmsInventoryLossStockRequest> lossStocks) {
		try {
			var root = parse(original);
			if (root.path("schemaVersion").isInt() && root.path("schemaVersion").intValue() == 3) {
				return WmsInventoryLossSnapshot.matches(row, original, selected, confirmation, lossStocks);
			}
			require(lossStocks == null || lossStocks.isEmpty(), "原申请不含指定实物");
			Frozen stored = read(row, original, false);
			if (stored.version() == 2) {
				return parse(original).equals(parse(capture(row, selected, confirmation)));
			}
			require(selected != null && confirmation == null && selected.getStockOwnerType() == null
					&& StrUtil.isBlank(selected.getStockOwnerCode()) && StrUtil.isBlank(selected.getSourceBusinessNo())
					&& selected.getMatUnit() == null, "历史申请仅按原物理属性核对，不能追加货权或来源声明");
			return parse(original).equals(parse(write(physical(selected))));
		} catch (RuntimeException ex) {
			return false;
		}
	}

	public static Frozen read(WmsInsideInventoryPlanDetailEntity row, String text, boolean posting) {
		JsonNode node = parse(text);
		require(node != null && node.isObject(), "原审批物料属性格式无效");
		if (node.path("schemaVersion").isInt() && node.path("schemaVersion").intValue() == 3) {
			return WmsInventoryLossSnapshot.read(row, node, posting);
		}
		require(text.length() <= 16384, "原审批物料属性过长");
		var names = new java.util.HashSet<String>();
		node.fieldNames().forEachRemaining(names::add);
		boolean v2 = node.has("schemaVersion");
		require(names.equals(v2 ? V2 : Set.copyOf(PHYSICAL)), "原审批物料属性缺失或版本不支持");
		if (v2) {
			require(node.path("schemaVersion").isIntegralNumber() && node.path("schemaVersion").canConvertToInt()
							&& node.path("schemaVersion").intValue() == 2,
					"原审批属性版本不支持");
		}
		var selected = new WmsInBillDetailEntity();
		selected.setMatBatch(text(node, "matBatch"));
		selected.setSupplierCode(text(node, "supplierCode"));
		selected.setSupplierName(text(node, "supplierName"));
		selected.setContainerCode(text(node, "containerCode"));
		selected.setMatBarcode(text(node, "matBarcode"));
		selected.setMatProduceDate(date(text(node, "matProduceDate")));
		selected.setMatValidEndDate(date(text(node, "matValidEndDate")));
		var period = node.get("matValidPeriod");
		require(period.isNull() || (period.isIntegralNumber() && period.canConvertToInt() && period.intValue() >= 0),
				"原审批有效期格式无效");
		selected.setMatValidPeriod(period.isNull() ? null : period.intValue());
		validatePhysical(row, selected);
		if (!v2) {
			require(!posting || !gain(row), "历史盘盈申请未记录货权确认，请驳回或撤销后重新申请");
			return new Frozen(1, null, selected);
		}
		selected.setStockOwnerType(text(node, "stockOwnerType"));
		selected.setStockOwnerCode(text(node, "stockOwnerCode"));
		selected.setSourceBusinessNo(text(node, "sourceBusinessNo"));
		selected.setMatUnit(text(node, "matUnit"));
		require(node.get("ownershipConfirmed").isBoolean(), "原审批货权确认依据不完整");
		boolean confirmed = node.get("ownershipConfirmed").booleanValue();
		require("OWNED".equals(selected.getStockOwnerType()) && selected.getStockOwnerCode() == null
				&& StrUtil.isNotBlank(row.getMatUnit()) &&
				java.util.Objects.equals(row.getMatUnit(), selected.getMatUnit()) && (!gain(row) || confirmed) &&
				(!confirmed || gain(row))
				&& (gain(row) || selected.getSourceBusinessNo() == null), "原审批货权、单位或来源与盘点不一致");
		require(java.util.Objects.equals(selected.getSourceBusinessNo(), source(selected.getSourceBusinessNo())),
				"原审批物料来源格式无效");
		return new Frozen(2, confirmed, selected);
	}

	/**
	 * 首笔收发已执行但仍在原事务内，回执不符会把库存、流水和状态一起回滚。
	 */
	public static void requireReceipt(WmsInsideInventoryPlanDetailEntity row, Frozen frozen,
	                                  WmsInBillDetailEntity receipt, boolean inbound) {
		require(receipt != null && StrUtil.isNotBlank(row.getMatUnit()) &&
				row.getMatUnit().equals(receipt.getMatUnit()), "损益回执单位与原盘点不一致，未完成调账");
		String owner = receipt.getStockOwnerType();
		require(("OWNED".equals(owner) ||
				((frozen.version() == 1 || frozen.version() == 3) && StrUtil.isBlank(owner))) &&
				StrUtil.isBlank(receipt.getStockOwnerCode()), "损益回执货权与自有库存审批不一致，未完成调账");
		var expected = frozen.detail();
		boolean exact = inbound && frozen.version() == 2;
		require(matchesAttribute(expected.getMatBatch(), receipt.getMatBatch(), exact)
						&& matchesAttribute(expected.getSupplierCode(), receipt.getSupplierCode(), exact)
						&& (!exact || java.util.Objects.equals(expected.getSupplierName(), receipt.getSupplierName()))
						&& matchesAttribute(expected.getContainerCode(), receipt.getContainerCode(), exact)
						&& matchesAttribute(expected.getMatBarcode(), receipt.getMatBarcode(), exact)
						&& matchesAttribute(expected.getMatProduceDate(), receipt.getMatProduceDate(), exact)
						&& matchesAttribute(expected.getMatValidPeriod(), receipt.getMatValidPeriod(), exact)
						&& matchesAttribute(expected.getMatValidEndDate(), receipt.getMatValidEndDate(), exact),
				"实际损益物料属性与批准内容不一致，请撤销批准后重新核对条码、容器及效期");
		if (inbound && frozen.version() == 2) {
			require(java.util.Objects.equals(expected.getSourceBusinessNo(), source(receipt.getSourceBusinessNo())),
					"盘盈来源与批准内容不一致，未完成调账");
		}
	}

	private static boolean matchesAttribute(Object expected, Object actual, boolean exact) {
		if (exact) {
			return java.util.Objects.equals(expected, actual);
		}
		return expected == null || (expected instanceof String s && s.isEmpty()) ||
				java.util.Objects.equals(expected, actual);
	}

	private static void validatePhysical(WmsInsideInventoryPlanDetailEntity row, WmsInBillDetailEntity selected) {
		require(row != null && selected != null, "原盘点范围或物料属性缺失");
		require(StrUtil.isBlank(row.getMatBatch()) ||
				java.util.Objects.equals(row.getMatBatch(), selected.getMatBatch()), "损益批次与原盘点范围不一致");
		if (gain(row)) {
			require(StrUtil.isAllNotBlank(selected.getMatBatch(), selected.getSupplierCode(),
							selected.getSupplierName()) && selected.getMatProduceDate() != null,
					"盘盈须明确批次、供应商和生产日期");
		}
		require(selected.getMatValidPeriod() == null || selected.getMatValidPeriod() >= 0, "物料有效期不能为负数");
		require(selected.getMatProduceDate() == null || selected.getMatValidEndDate() == null ||
				!selected.getMatValidEndDate().isBefore(selected.getMatProduceDate()), "到期日不能早于生产日期");
	}

	private static boolean gain(WmsInsideInventoryPlanDetailEntity row) {
		return number(row.getInventoryNum(), "实盘数量").compareTo(number(row.getCurrentNumber(), "账面数量")) > 0;
	}

	private static String source(String value) {
		if (value == null || value.isEmpty()) {
			return null;
		}
		require(value.equals(value.trim()) && value.length() <= 128 && !value.chars().anyMatch(Character::isISOControl),
				"物料来源单号格式无效");
		return value;
	}

	private static Map<String, Object> physical(WmsInBillDetailEntity d) {
		var map = new LinkedHashMap<String, Object>();
		map.put("matBatch", d.getMatBatch());
		map.put("supplierCode", d.getSupplierCode());
		map.put("supplierName", d.getSupplierName());
		map.put("containerCode", d.getContainerCode());
		map.put("matBarcode", d.getMatBarcode());
		map.put("matProduceDate", d.getMatProduceDate() == null ? null : d.getMatProduceDate().toString());
		map.put("matValidPeriod", d.getMatValidPeriod());
		map.put("matValidEndDate", d.getMatValidEndDate() == null ? null : d.getMatValidEndDate().toString());
		return map;
	}

	private static String text(JsonNode n, String key) {
		var v = n.get(key);
		require(v != null && (v.isNull() || v.isTextual()), "原审批属性类型无效");
		return v.isNull() ? null : v.textValue();
	}

	private static LocalDate date(String value) {
		if (value == null) {
			return null;
		}
		try {
			return LocalDate.parse(value);
		} catch (RuntimeException ex) {
			throw new CheckedException("原审批日期格式无效");
		}
	}

	static JsonNode parse(String value) {
		try {
			require(value != null && value.length() <= 131072, "原审批物料属性缺失或过长");
			return JSON.readTree(value);
		} catch (java.io.IOException ex) {
			throw new CheckedException("原审批物料属性无法读取");
		}
	}

	static String write(Object value) {
		try {
			return JSON.writeValueAsString(value);
		} catch (java.io.IOException ex) {
			throw new CheckedException("盘点物料属性保存失败");
		}
	}

	public record Frozen(int version, Boolean ownershipConfirmed, WmsInBillDetailEntity detail,
	                     List<WmsInventoryLossSnapshot.Stock> lossStocks) {
		public Frozen(int version, Boolean ownershipConfirmed, WmsInBillDetailEntity detail) {
			this(version, ownershipConfirmed, detail, List.of());
		}
	}
}
