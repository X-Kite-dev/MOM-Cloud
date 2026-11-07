package com.kitex.mom.wms.service.impl.externalinventory;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.wms.dto.WmsExternalInventoryLossDTO.*;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.DateTimeException;
import java.util.*;

import static com.kitex.mom.wms.service.impl.WmsInventoryPlanAccess.require;
import static com.kitex.mom.wms.service.inventoryapproval.WmsInventoryApprovalRecoveryCodec.id;

/**
 * 外部原号严格解析器，只有首次prepare可以规范化分配顺序、数量和意见。
 */
@Component
public class ExternalLossCodec {
	private final ObjectMapper json = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
			.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

	/**
	 * 从原始HTTP正文核信封字段，避免全局对象映射丢失重复键或转换类型。
	 */
	public Envelope envelope(String raw) {
		var n = object(raw);
		fields(n, Envelope.class);
		return new Envelope(text(n, "requestId", 64), text(n, "commandText", 131072, true));
	}

	/**
	 * 依实际URL绑定原号域；先核全部静态字段，再用固定DTO生成首存原文。
	 */
	public Parsed parse(String domain, Envelope e, boolean prepare) {
		require(e != null && e.commandText() != null &&
						e.commandText().getBytes(StandardCharsets.UTF_8).length <= 131072,
				"命令原文超过128KiB或缺失");
		var n = object(e.commandText());
		Class<?> command = switch (domain) {
			case "OA1" -> ApprovalCommand.class;
			case "OM1" -> MandateCommand.class;
			case "OE1" -> EvidenceCommand.class;
			default -> throw new CheckedException("外部命令域无效");
		};
		fields(n, command);
		require(n.path("version").isInt() && n.path("version").intValue() == 1, "外部命令版本无效");
		var scope = n.path("scope");
		boolean mandate = "OM1".equals(domain);
		fields(scope, mandate ? WarehouseScope.class : DetailScope.class);
		String tenant = identifier(scope, "tenantId");
		String actor = identifier(scope, "actorId");
		String warehouse = org(scope, "warehouseCode");
		String factory = org(scope, "factoryCode");
		String plan = mandate ? null : identifier(scope, "planId");
		String detail = mandate ? null : identifier(scope, "planDetailId");
		require(e.requestId() != null &&
						e.requestId().matches(domain + "_" + actor + "_[0-9a-f]{12}4[0-9a-f]{3}[89ab][0-9a-f]{15}"),
				"原号与实名人员或命令域不符");
		String action = text(n, "action", 8);
		switch (domain) {
			case "OA1" -> approval(n, action);
			case "OM1" -> mandate(n, action);
			case "OE1" -> evidence(n, action);
			default -> throw new CheckedException("外部命令域无效");
		}
		try {
			Object typed = json.treeToValue(n, command);
			String canonical = json.writeValueAsString(typed);
			require(canonical.getBytes(StandardCharsets.UTF_8).length <= 131072 &&
							(prepare || canonical.equals(e.commandText())),
					"必须使用prepare返回的原始命令");
			return new Parsed(domain, action, new DetailScope(tenant, actor, factory, warehouse, plan, detail),
					mandate ? json.treeToValue(scope, WarehouseScope.class) : json.treeToValue(scope,
							DetailScope.class),
					new Envelope(e.requestId(), canonical), typed);
		} catch (java.io.IOException ex) {
			throw new CheckedException("命令类型或序列化无效");
		}
	}

	/**
	 * wireScope保留OM仓域四字段与OA/OE明细六字段的区别。
	 */
	public record Parsed(String domain, String action, DetailScope scope, Object wireScope, Envelope envelope,
	                     Object typed) {
	}

	private void approval(ObjectNode n, String action) {
		require(Set.of("SUBMIT", "APPROVE", "REJECT", "REVOKE", "POST").contains(action), "不支持的盘亏动作");
		if ("SUBMIT".equals(action)) {
			empty(n, "review", "post");
			var s = branch(n, "submit", Submit.class);
			BigDecimal book = quantity(s, "expectedCurrentNumber", false);
			BigDecimal count = quantity(s, "expectedInventoryNum", false);
			identifier(s, "recountId");
			hash(s, "basisVersion");
			hash(s, "groupKey");
			identifier(s, "mandateId");
			version(s, "mandateVersion");
			identifier(s, "evidenceId");
			hash(s, "evidenceSha256");
			note(s, "reason");
			var stocks = s.path("lossStocks");
			require(stocks.isArray() && stocks.size() > 0 && stocks.size() <= 100, "必须指定1至100个精确实物");
			var sorted = new TreeMap<Long, JsonNode>();
			BigDecimal sum = BigDecimal.ZERO;
			for (JsonNode stock : stocks) {
				fields(stock, LossStock.class);
				require(sorted.put(id(identifier(stock, "stockId")), stock) == null, "同一实物不能重复分配");
				hash(stock, "basisVersion");
				sum = sum.add(quantity((ObjectNode) stock, "quantity", true));
			}
			require(sum.compareTo(book.subtract(count)) == 0, "精确分配合计须等于盘亏量");
			var a = s.putArray("lossStocks");
			sorted.values().forEach(a::add);
		} else if ("POST".equals(action)) {
			empty(n, "submit", "review");
			identifier(branch(n, "post", Post.class), "approvalId");
		} else {
			empty(n, "submit", "post");
			var r = branch(n, "review", Review.class);
			identifier(r, "approvalId");
			note(r, "comment");
			if ("APPROVE".equals(action)) {
				identifier(r, "evidenceId");
				hash(r, "evidenceSha256");
				require(r.path("verified").isBoolean() && r.path("verified").booleanValue(), "批准须明确核验同一原件");
			} else {
				empty(r, "evidenceId", "evidenceSha256", "verified");
			}
		}
	}

	private void mandate(ObjectNode n, String action) {
		require(Set.of("PROPOSE", "APPROVE", "REJECT", "REVOKE").contains(action), "不支持的授权动作");
		if ("PROPOSE".equals(action)) {
			empty(n, "decision");
			var p = branch(n, "proposal", Proposal.class);
			owner(p);
			require("LOSS".equals(text(p, "direction", 4)), "仅支持盘亏授权");
			require(!date(p, "validFrom").isAfter(date(p, "validUntil")), "授权有效期间无效");
			text(p, "authorityNo", 128);
		} else {
			empty(n, "proposal");
			var d = branch(n, "decision", Decision.class);
			identifier(d, "mandateId");
			version(d, "expectedVersion");
			note(d, "comment");
		}
	}

	private void evidence(ObjectNode n, String action) {
		require("REGISTER".equals(action), "仅支持原件登记");
		var r = branch(n, "registration", Registration.class);
		identifier(r, "recountId");
		hash(r, "basisVersion");
		hash(r, "groupKey");
		identifier(r, "mandateId");
		version(r, "mandateVersion");
		owner(r);
		text(r, "sourceBusinessNo", 128);
		text(r, "matCode", 128);
		text(r, "matUnit", 100);
		// 确认件记录的是numeric汇总；每个实际扣库份额在SUBMIT合同中单独验证Double精度。
		BigDecimal total = decimalText(r, "lossQuantity");
		require(total.signum() > 0 && total.scale() <= 8, "证件汇总数量须为最多8位小数的正数");
		text(r, "confirmationNo", 128);
		date(r, "confirmationDate");
		String file = text(r, "fileName", 200);
		require(file.equals(file.trim()) && !Set.of(".", "..").contains(file) && !file.contains("/") &&
						!file.contains("\\"),
				"文件名含非法路径");
		require(Set.of("application/pdf", "image/jpeg", "image/png").contains(text(r, "mime", 50)),
				"仅支持PDF、JPEG或PNG");
		require(r.path("byteLength").isInt() && r.path("byteLength").intValue() > 0 &&
						r.path("byteLength").intValue() <= 4194304,
				"文件须大于0且最多4MiB");
		hash(r, "sha256");
	}

	private void owner(JsonNode n) {
		require(Set.of("CUSTOMER_SUPPLIED", "CONSIGNED").contains(text(n, "ownerType", 20)), "外部货权类型无效");
		String code = text(n, "ownerCode", 64);
		require(code.equals(code.trim()), "货主编码不能含首尾空白");
		identifier(n, "mdmOwnerId");
	}

	private String org(JsonNode n, String k) {
		String v = text(n, k, 100);
		require(v.equals(v.trim()) && !Set.of(".", "..").contains(v) && v.codePoints().noneMatch(c ->
						(c != ' ' && (Character.isWhitespace(c) || Character.isSpaceChar(c))) || "/\\?#%*".indexOf(c) >= 0),
				"组织编码无效");
		return v;
	}

	private void version(JsonNode n, String k) {
		require(id(identifier(n, k)) <= Integer.MAX_VALUE, "授权版本超出范围");
	}

	private String identifier(JsonNode n, String k) {
		String v = text(n, k, 19);
		id(v);
		return v;
	}

	private void hash(JsonNode n, String k) {
		require(text(n, k, 64).matches("[a-f0-9]{64}"), "摘要格式无效");
	}

	private LocalDate date(JsonNode n, String k) {
		String v = text(n, k, 10);
		require(v.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") && !v.startsWith("0000"), "日期格式无效");
		try {
			return LocalDate.parse(v);
		} catch (DateTimeException ex) {
			throw new CheckedException("日期无效");
		}
	}

	private void note(ObjectNode n, String k) {
		String v = text(n, k, 131072).trim();
		require(!v.isBlank() && v.length() <= 500, "意见须为1至500字");
		n.put(k, v);
	}

	private BigDecimal quantity(ObjectNode n, String k, boolean allocation) {
		BigDecimal q = decimalText(n, k);
		require(Double.isFinite(q.doubleValue()) && BigDecimal.valueOf(q.doubleValue()).compareTo(q) == 0 &&
				(!allocation || (q.signum() > 0 && q.scale() <= 8)), "数量精度或方向无效");
		return q;
	}

	private BigDecimal decimalText(ObjectNode n, String k) {
		String v = text(n, k, 350);
		require(v.matches("(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?"), "数量须为非负普通十进制文本");
		BigDecimal q = new BigDecimal(v).stripTrailingZeros();
		n.put(k, q.toPlainString());
		return q;
	}

	private void fields(JsonNode n, Class<?> type) {
		require(n != null && n.isObject() && n.size() == type.getRecordComponents().length, "协议对象含缺失或未知字段");
		for (var f : type.getRecordComponents()) {
			require(n.has(f.getName()), "协议字段缺失：" + f.getName());
		}
	}

	private ObjectNode branch(ObjectNode n, String k, Class<?> type) {
		fields(n.get(k), type);
		return (ObjectNode) n.get(k);
	}

	private void empty(JsonNode n, String... keys) {
		for (String key : keys) {
			require(n.path(key).isNull(), "动作互斥字段必须明确为空：" + key);
		}
	}

	private String text(JsonNode n, String k, int max) {
		return text(n, k, max, false);
	}

	private String text(JsonNode n, String k, int max, boolean raw) {
		var node = n.path(k);
		require(node.isTextual(), "字段须为文本：" + k);
		String value = node.textValue();
		require(!value.isBlank() && value.length() <= max && value.codePoints().noneMatch(c ->
				(c >= 0xd800 && c <= 0xdfff) || (!raw && Character.isISOControl(c))), "字段长度或字符无效：" + k);
		return value;
	}

	private ObjectNode object(String raw) {
		try {
			require(raw != null, "缺少JSON原文");
			var parsed = json.readTree(raw);
			require(parsed != null && parsed.isObject(), "应为JSON对象");
			return (ObjectNode) parsed;
		} catch (java.io.IOException ex) {
			throw new CheckedException("JSON原文无效或含重复字段");
		}
	}
}
