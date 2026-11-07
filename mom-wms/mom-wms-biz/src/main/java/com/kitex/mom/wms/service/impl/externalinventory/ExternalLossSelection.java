package com.kitex.mom.wms.service.impl.externalinventory;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.wms.dto.WmsExternalInventoryLossDTO.*;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static com.kitex.mom.wms.service.impl.WmsInventoryPlanAccess.require;
import static com.kitex.mom.wms.service.inventoryapproval.WmsInventoryApprovalRecoveryCodec.id;

/**
 * v4精确分配冻结及再核验；普通旧审批读取路径不得把它当作v1至v3。
 */
@Component
public class ExternalLossSelection {
	private final ObjectMapper json = new ObjectMapper();

	/**
	 * 冻结整组来源、原件与经无损校验的分配；返回固定v4原件，拒绝截断、替代和拆单。
	 */
	ObjectNode build(DetailScope scope, ExternalLossBasis.Facts facts, Evidence e, List<LossStock> allocation) {
		var b = facts.basis();
		var m = b.mandate();
		var r = e.registration();
		ExternalLossEvidence.check(b, r);
		var f = json.createObjectNode();
		f.put("schemaVersion", 4).put("mode", "EXTERNAL_EXACT_LOSS");
		f.putObject("scope").put("tenantId", scope.tenantId()).put("factoryCode", scope.factoryCode())
				.put("warehouseCode", scope.warehouseCode())
				.put("planId", scope.planId()).put("planDetailId", scope.planDetailId())
				.put("siteCode", facts.detail().getSiteCode()).put("matCode", b.matCode())
				.put("matBatch", facts.detail().getMatBatch()).put("matUnit", b.matUnit());
		f.putObject("recount").put("id", b.recountId()).put("requestId", facts.recountRequest())
				.put("basisVersion", b.basisVersion()).put("groupKey", b.groupKey())
				.put("book", b.expectedCurrentNumber()).put("count", b.expectedInventoryNum())
				.put("delta", "-" + b.lossQuantity());
		f.putObject("owner").put("type", b.owner().ownerType()).put("id", b.owner().id()).put("code", b.owner().code())
				.put("name", b.owner().name())
				.put("identityReadAt", facts.identityReadAt());
		f.put("sourceBusinessNo", b.sourceBusinessNo());
		f.putObject("mandate").put("id", m.id()).put("version", m.version()).put("from", m.validFrom())
				.put("until", m.validUntil()).put("authorityNo", m.authorityNo())
				.put("reviewerId", m.reviewerId()).put("reviewedAt", m.reviewedAt());
		f.putObject("confirmation").put("id", e.id()).put("hash", r.sha256()).put("no", r.confirmationNo())
				.put("date", r.confirmationDate())
				.put("registeredBy", e.registeredBy()).put("registeredAt", e.registeredAt())
				.put("length", r.byteLength()).put("mime", r.mime());
		var sources = f.putArray("sources");
		var pieces = f.putArray("lossStocks");
		var chosen = new LinkedHashMap<String, LossStock>();
		for (var v : allocation) {
			require(chosen.put(v.stockId(), v) == null, "同一实物不能重复分配");
		}
		var total = BigDecimal.ZERO;
		for (int j = 0; j < b.sources().size(); j++) {
			var source = b.sources().get(j);
			var stock = facts.stocks().get(j);
			var physical = json.valueToTree(ExternalLossProvenance.physical(stock));
			sources.addObject().put("stockId", source.stockId()).put("inBillId", source.inBillId())
					.put("inBillNo", source.inBillNo()).put("inBillDetailId", source.inBillDetailId())
					.put("inboundFlowId", source.inboundFlowId()).put("operationRequestId", source.operationRequestId())
					.put("inboundQuantity", source.inboundQuantity())
					.put("currentQuantity", source.currentQuantity()).set("physical", physical);
			var piece = chosen.remove(source.stockId());
			if (piece == null) {
				continue;
			}
			var c = facts.candidates().get(j);
			var q = new BigDecimal(piece.quantity());
			var remainder = new BigDecimal(c.currentQuantity()).subtract(q);
			require(c.selectable() && piece.basisVersion().equals(c.basisVersion()) && q.signum() > 0 &&
							q.scale() <= 8 &&
							q.compareTo(new BigDecimal(c.availableQuantity())) <= 0 &&
							(!Boolean.TRUE.equals(c.serialManaged()) || q.compareTo(BigDecimal.ONE) == 0) &&
							Double.isFinite(remainder.doubleValue()) &&
							remainder.compareTo(BigDecimal.valueOf(remainder.doubleValue())) == 0,
					"所选实物已变化、可用不足或余额无法无损落账");
			pieces.addObject().put("stockId", piece.stockId()).put("basisVersion", piece.basisVersion())
					.put("quantity", piece.quantity())
					.put("currentQuantity", c.currentQuantity()).put("availableQuantity", c.availableQuantity())
					.set("physical", physical);
			total = total.add(q);
		}
		require(chosen.isEmpty() && total.compareTo(new BigDecimal(b.lossQuantity())) == 0 &&
						f.toString().getBytes(StandardCharsets.UTF_8).length <= 2097152,
				"分配超出完整范围、合计不守恒或冻结正文超过2MiB");
		return f;
	}

	/**
	 * 只接受专用外部v4冻结正文，不对历史版本作宽松解析。
	 */
	ObjectNode read(String raw) {
		try {
			require(raw != null && raw.getBytes(StandardCharsets.UTF_8).length <= 2097152, "外部冻结原件缺失或超限");
			var f = json.readTree(raw);
			require(f != null && f.isObject() && f.path("schemaVersion").isInt() &&
							f.path("schemaVersion").intValue() == 4
							&& "EXTERNAL_EXACT_LOSS".equals(f.path("mode").textValue()) && f.path("lossStocks").isArray() &&
							f.path("lossStocks").size() > 0 && f.path("lossStocks").size() <= 100,
					"外部冻结原件版本或分配无效");
			return (ObjectNode) f;
		} catch (java.io.IOException ex) {
			throw new CheckedException("外部冻结原件已损坏");
		}
	}

	/**
	 * 按当前实盘、证件与全部源行重建原冻结依据，保留原始权威读取时刻。
	 */
	void verify(DetailScope s, ExternalLossBasis.Facts facts, Evidence e, ObjectNode frozen) {
		var pieces = new ArrayList<LossStock>();
		for (var p : frozen.path("lossStocks")) {
			pieces.add(new LossStock(p.path("stockId").textValue(), p.path("basisVersion").textValue(),
					p.path("quantity").textValue()));
		}
		var current = build(s, facts, e, pieces);
		// 保存首次权威读取时点，仅事实比较时排除本次查询的新时间。原ID、编码、名称、有效性均已重核。
		((ObjectNode) current.path("owner")).set("identityReadAt", frozen.path("owner").path("identityReadAt"));
		require(current.equals(frozen), "原件、复盘、授权、原始来源或当前可扣实物已变化，请撤销重新申请");
	}

	/**
	 * 从一次已受理申请的不可变正文抽取实物行；当时的总量、来源与每行分配必须同时成立。
	 */
	FrozenAllocations allocations(DetailScope scope, String approvalId, String raw, String actualMaterial,
	                              String actualUnit, String actualBatch, String actualSite) {
		var frozen = read(raw);
		var area = frozen.path("scope");
		require(scope.tenantId().equals(value(area, "tenantId")) &&
				scope.factoryCode().equals(value(area, "factoryCode"))
				&& scope.warehouseCode().equals(value(area, "warehouseCode")) &&
				scope.planId().equals(value(area, "planId"))
				&& scope.planDetailId().equals(value(area, "planDetailId")), "原申请冻结范围与实名明细不一致");
		String material = value(area, "matCode");
		String unit = value(area, "matUnit");
		String site = value(area, "siteCode");
		require(material.equals(actualMaterial) && unit.equals(actualUnit) && site.equals(actualSite)
						&& Objects.equals(nullableValue(area, "matBatch"), actualBatch),
				"原申请物料、单位、批次或库位与实际明细不符");

		var owner = frozen.path("owner");
		String ownerType = value(owner, "type");
		require(Set.of("CONSIGNED", "CUSTOMER_SUPPLIED").contains(ownerType), "原申请外部货权无效");
		String ownerCode = value(owner, "code");
		String ownerName = value(owner, "name");
		String business = value(frozen, "sourceBusinessNo");
		var recount = frozen.path("recount");
		var book = frozenNumber(recount, "book");
		var count = frozenNumber(recount, "count");
		var loss = book.subtract(count).stripTrailingZeros();
		require(loss.signum() > 0 && loss.scale() <= 8 && loss.toPlainString().length() <= 350
				&& ("-" + loss.toPlainString()).equals(value(recount, "delta")), "原复盘汇总与盘亏方向不符");

		var sources = frozenSources(frozen.path("sources"), area, ownerType, ownerCode, business, book);
		var rows = new ArrayList<FrozenAllocation>();
		BigDecimal total = BigDecimal.ZERO;
		long previous = 0;
		for (var piece : frozen.path("lossStocks")) {
			String stockId = value(piece, "stockId");
			long key = id(stockId);
			var source = sources.get(key);
			require(key > previous && source != null && value(piece, "basisVersion").matches("[0-9a-f]{64}"),
					"原申请分配顺序、实物依据或来源无效");
			var physical = piece.path("physical");
			require(physical.isObject() && physical.equals(source.path("physical")), "原申请分配与实收来源物性不符");
			var q = frozenNumber(piece, "quantity");
			var current = frozenNumber(piece, "currentQuantity");
			var available = frozenNumber(piece, "availableQuantity");
			require(q.signum() > 0 && q.scale() <= 8 && exactDouble(q) && exactDouble(current)
							&& current.compareTo(frozenNumber(source, "currentQuantity")) == 0 && q.compareTo(available) <= 0
							&& available.compareTo(current) <= 0 && exactDouble(current.subtract(q)),
					"原申请分配、库存或可用量无效");
			if (physical.path("serialManaged").booleanValue()) {
				require(q.compareTo(BigDecimal.ONE) == 0 && current.compareTo(BigDecimal.ONE) == 0
						&& !value(physical, "matBarcode").isBlank(), "原申请序列件数量或条码无效");
			}
			rows.add(
					new FrozenAllocation(stockId, q.toPlainString(), current.toPlainString(), available.toPlainString(),
							unit,
							displayValue(physical, "matBatch"), displayValue(physical, "matBarcode"),
							displayValue(physical, "containerCode"), site,
							displayValue(physical, "supplierName"), value(source, "inBillNo")));
			total = total.add(q);
			previous = key;
		}
		require(total.compareTo(loss) == 0, "原申请逐笔分配合计与复盘差额不一致");
		return new FrozenAllocations(scope, approvalId, material, unit, ownerType, ownerCode, ownerName, business,
				loss.toPlainString(), List.copyOf(rows));
	}

	/**
	 * 核完整来源集合与账面合计，按实物主键建立唯一索引，防止用其他入库行替换缺失来源。
	 */
	private Map<Long, JsonNode> frozenSources(JsonNode sources, JsonNode area, String ownerType,
	                                          String ownerCode, String business, BigDecimal book) {
		require(sources.isArray() && sources.size() > 0 && sources.size() <= 100, "冻结实收来源数量无效");
		var index = new HashMap<Long, JsonNode>();
		var details = new HashSet<Long>();
		var flows = new HashSet<Long>();
		long previous = 0;
		BigDecimal sum = BigDecimal.ZERO;
		for (var source : sources) {
			long key = id(value(source, "stockId"));
			long detail = id(value(source, "inBillDetailId"));
			id(value(source, "inBillId"));
			value(source, "inBillNo");
			require(key > previous && details.add(detail) && flows.add(id(value(source, "inboundFlowId")))
							&& ("IN-STOCK:" + detail).equals(value(source, "operationRequestId")),
					"冻结实收来源重复、无序或原操作号不符");
			var physical = source.path("physical");
			require(physical.isObject() && physical.path("serialManaged").isBoolean()
					&& ownerType.equals(value(physical, "stockOwnerType")) &&
					ownerCode.equals(value(physical, "stockOwnerCode"))
					&& business.equals(value(physical, "sourceBusinessNo")), "实收来源货权或商业归属不符");
			for (String k : List.of("warehouseCode", "siteCode", "matCode", "matUnit")) {
				require(value(area, k).equals(value(physical, k)), "冻结来源与申请范围不符：" + k);
			}
			String batch = nullableValue(area, "matBatch");
			String sourceBatch = nullableValue(physical, "matBatch");
			require(batch == null || batch.isBlank() || batch.equals(sourceBatch), "冻结批次不符");
			var inbound = frozenNumber(source, "inboundQuantity");
			var current = frozenNumber(source, "currentQuantity");
			require(current.signum() > 0 && inbound.compareTo(current) >= 0 && exactDouble(inbound) &&
					exactDouble(current), "冻结实收数量或原库存量无效");
			sum = sum.add(current);
			index.put(key, source);
			previous = key;
		}
		require(sum.compareTo(book) == 0, "完整来源合计与原申请账面数量不符");
		return index;
	}

	private static boolean exactDouble(BigDecimal n) {
		return Double.isFinite(n.doubleValue()) && BigDecimal.valueOf(n.doubleValue()).compareTo(n) == 0;
	}

	private static BigDecimal frozenNumber(JsonNode n, String key) {
		String text = value(n, key);
		require(text.length() <= 350 && text.matches("(?:0|[1-9][0-9]*)(?:\\.[0-9]*[1-9])?"),
				"冻结数量不是规范非负普通十进制：" + key);
		return new BigDecimal(text);
	}

	private static String value(JsonNode n, String key) {
		String v = nullableValue(n, key);
		require(v != null && !v.isBlank(), "冻结字段不能为空：" + key);
		return v;
	}

	private static String nullableValue(JsonNode n, String key) {
		var field = n.path(key);
		require(field.isNull() ||
						(field.isTextual() && field.textValue().codePoints().noneMatch(Character::isISOControl)),
				"冻结字段缺失或类型无效：" + key);
		return field.textValue();
	}

	private static String displayValue(JsonNode n, String key) {
		String v = nullableValue(n, key);
		// 仅展示时将历史可空物流属性的空白表示为null，物性比较在此之前按原JSON逐项完成。
		return v == null || v.isBlank() ? null : v;
	}
}
