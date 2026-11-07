package com.kitex.mom.wms.service.inventoryapproval;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.wms.dto.WmsInventoryApprovalRecoveryDTO.*;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;

import static com.kitex.mom.wms.service.impl.WmsInventoryPlanAccess.require;

/**
 * 严格静态协议校验与首次规范化；不读取业务现态、不分配物料编码。
 */
@Component
public class WmsInventoryApprovalRecoveryCodec {
	private final ObjectMapper json = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
			.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

	/**
	 * HTTP原始信封由专用严格解析器读取，避免全局Jackson宽松配置吞掉重复键或额外字段。
	 */
	public Envelope envelope(String raw) {
		JsonNode node;
		try {
			require(raw != null, "审批信封不能为空");
			node = json.readTree(raw);
		} catch (java.io.IOException ex) {
			throw new CheckedException("审批信封JSON格式无效");
		}
		fields(node, "requestId", "commandText");
		require(node.get("requestId").isTextual() && node.get("commandText").isTextual(), "信封字段须为文本");
		return new Envelope(node.get("requestId").textValue(), node.get("commandText").textValue());
	}

	/**
	 * 首次规范化后返回可首存信封；后续入口只接受已经规范化的原文。
	 */
	public Envelope prepare(Envelope e) {
		ObjectNode n = node(e, true);
		return new Envelope(e.requestId(), write(json.valueToTree(convert(n, Command.class))));
	}

	/**
	 * 完整解析冻结命令，格式拒绝不会伪装成ABSENT。
	 */
	public Command command(Envelope e) {
		Command command = convert(node(e, false), Command.class);
		// 与prepare同一DTO字段序和同一serializer；不接受首尾空白、异转义或重排属性的首次原文。
		require(e.commandText().equals(write(json.valueToTree(command))),
				"审批原文不是prepare返回的规范JSON，请核对首存原件");
		return command;
	}

	/**
	 * 正规正int64主键，拒绝前导零、指数和越界。
	 */
	public static long id(String s) {
		require(s != null && s.matches("[1-9][0-9]{0,18}"), "业务ID必须为正规正int64文本");
		try {
			return Long.parseLong(s);
		} catch (NumberFormatException ex) {
			throw new CheckedException("业务ID超出int64范围");
		}
	}

	/**
	 * 仅用命令内部事实校验固定版本、白名单、one-of及全部可再解析边界。
	 */
	private ObjectNode node(Envelope e, boolean initial) {
		require(e != null && e.commandText() != null &&
						e.commandText().getBytes(StandardCharsets.UTF_8).length <= 131072,
				"审批命令缺失或超过128KiB");
		JsonNode parsed;
		try {
			parsed = json.readTree(e.commandText());
		} catch (java.io.IOException ex) {
			throw new CheckedException("审批命令JSON格式无效");
		}
		fields(parsed, "version", "scope", "action", "submit", "review");
		ObjectNode n = (ObjectNode) parsed;
		require(n.get("version").isInt() && n.get("version").intValue() == 1, "审批协议版本无效");
		JsonNode s = n.get("scope");
		fields(s, "tenantId", "actorId", "factoryCode", "warehouseCode", "planId", "planDetailId");
		for (String k : List.of("tenantId", "actorId", "planId", "planDetailId")) {
			id(text(s, k, false, 19));
		}
		for (String k : List.of("factoryCode", "warehouseCode")) {
			String value = text(s, k, false, 100);
			require(value.equals(value.trim()) && !List.of(".", "..").contains(value)
					&& value.codePoints()
					.noneMatch(c -> (c != ' ' && (Character.isWhitespace(c) || Character.isSpaceChar(c)))
							|| "/\\?#%*".indexOf(c) >= 0), "实际工厂仓库编码无效");
		}
		String actor = s.get("actorId").textValue();
		require(e.requestId() != null && e.requestId().length() >= 16 && e.requestId().length() <= 64
						&& e.requestId().matches("IA1_" + actor + "_[0-9a-f]{12}4[0-9a-f]{3}[89ab][0-9a-f]{15}"),
				"审批原号无效");
		String action = text(n, "action", false, 8);
		require(List.of("SUBMIT", "APPROVE", "REJECT", "REVOKE").contains(action), "审批动作无效");
		if ("SUBMIT".equals(action)) {
			require(n.get("review").isNull(), "申请不能附带审核内容");
			submit(n.get("submit"), initial);
		} else {
			require(n.get("submit").isNull(), "审核不能附带申请内容");
			fields(n.get("review"), "approvalId", "comment");
			id(text(n.get("review"), "approvalId", false, 19));
			note((ObjectNode) n.get("review"), "comment", initial);
		}
		return n;
	}

	/**
	 * 数量方向、货权确认和指定库存精确守恒；不借实时库存修补原文。
	 */
	private void submit(JsonNode raw, boolean initial) {
		fields(raw, "expectedCurrentNumber", "expectedInventoryNum", "detail", "ownershipConfirmed", "lossStocks",
				"reason");
		ObjectNode s = (ObjectNode) raw;
		BigDecimal current = quantity(s, "expectedCurrentNumber", initial, false);
		BigDecimal counted = quantity(s, "expectedInventoryNum", initial, false);
		int direction = counted.compareTo(current);
		require(direction != 0, "零差异不能申请损益审批");
		note(s, "reason", initial);
		require(s.get("ownershipConfirmed").isBoolean() &&
						s.get("ownershipConfirmed").booleanValue() == (direction > 0),
				"盘盈必须自有确认，盘亏不能追加确认");
		JsonNode d = s.get("detail");
		fields(d, "matBatch", "supplierCode", "supplierName", "containerCode", "matBarcode", "matProduceDate",
				"matValidPeriod", "matValidEndDate", "stockOwnerType", "stockOwnerCode", "sourceBusinessNo", "matUnit");
		for (String k : List.of("matBatch", "supplierCode", "supplierName", "containerCode", "matBarcode")) {
			text(d, k, true, "supplierName".equals(k) ? 500 : 128);
		}
		LocalDate production = date(d, "matProduceDate");
		LocalDate expiry = date(d, "matValidEndDate");
		require(production == null || expiry == null || !expiry.isBefore(production), "到期日不能早于生产日期");
		var period = d.get("matValidPeriod");
		require(period.isNull() || (period.isIntegralNumber() && period.canConvertToInt() && period.intValue() >= 0),
				"有效期无效");
		require("OWNED".equals(text(d, "stockOwnerType", false, 10)) && d.get("stockOwnerCode").isNull(),
				"本流程只办理自有库存");
		text(d, "matUnit", false, 100);
		String source = text(d, "sourceBusinessNo", true, 128);
		require(source == null || (!source.isEmpty() && source.equals(source.trim())), "来源单号无效");
		if (direction > 0) {
			for (String k : List.of("matBatch", "supplierCode", "supplierName")) {
				require(StrUtil.isNotBlank(d.get(k).asText(null)), "盘盈须明确批次与供应商");
			}
			require(production != null, "盘盈须明确生产日期");
		} else {
			require(source == null, "盘亏来源不能手填");
		}
		JsonNode stocks = s.get("lossStocks");
		require(stocks.isArray() && stocks.size() <= 100 && (direction < 0 || stocks.isEmpty()),
				"指定实物数量或方向无效");
		Set<Long> seen = new HashSet<>();
		List<JsonNode> sorted = new ArrayList<>();
		BigDecimal sum = BigDecimal.ZERO;
		long last = 0;
		for (JsonNode stock : stocks) {
			fields(stock, "stockId", "basisVersion", "quantity");
			long key = id(text(stock, "stockId", false, 19));
			require(seen.add(key) && (initial || key > last), "指定库存重复或顺序无效");
			last = key;
			require(text(stock, "basisVersion", false, 64).matches("[0-9a-f]{64}"), "库存依据无效");
			sum = sum.add(quantity((ObjectNode) stock, "quantity", initial, true));
			sorted.add(stock);
		}
		require(stocks.isEmpty() || sum.compareTo(current.subtract(counted)) == 0, "指定实物合计须等于盘亏量");
		if (initial) {
			sorted.sort(Comparator.comparingLong(a -> id(a.get("stockId").textValue())));
			ArrayNode a = json.createArrayNode();
			sorted.forEach(a::add);
			s.set("lossStocks", a);
		}
	}

	/**
	 * 仅首次允许Java旧Double正规化；指定库存始终校验精确数，不舍入。
	 */
	private BigDecimal quantity(ObjectNode n, String key, boolean initial, boolean loss) {
		String input = text(n, key, false, 1024);
		require(input.matches("(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?"), "数量文本无效");
		BigDecimal q;
		try {
			double value = Double.parseDouble(input);
			require(Double.isFinite(value) && value >= 0, "数量必须有限非负");
			q = BigDecimal.valueOf(value).stripTrailingZeros();
			if (loss) {
				BigDecimal exact = new BigDecimal(input).stripTrailingZeros();
				require(exact.signum() > 0 && exact.scale() <= 8 && exact.compareTo(q) == 0,
						"分配量须正数、最多8位小数且Double无损");
			}
		} catch (NumberFormatException ex) {
			throw new CheckedException("数量格式无效");
		}
		String canonical = q.signum() == 0 ? "0" : q.toPlainString();
		require(initial || input.equals(canonical), "请先prepare并保存规范数量原文");
		if (initial) {
			n.put(key, canonical);
		}
		return q;
	}

	private void note(ObjectNode n, String k, boolean initial) {
		String original = text(n, k, false, 131072);
		String v = StrUtil.trim(original);
		require(StrUtil.isNotBlank(v) && v.length() <= 500 && (initial || original.equals(v)),
				"原因意见须规范且1至500字");
		if (initial) {
			n.put(k, v);
		}
	}

	private LocalDate date(JsonNode n, String k) {
		String v = text(n, k, true, 10);
		if (v == null) {
			return null;
		}
		try {
			require(v.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") && !v.startsWith("0000"), "日期须真实ISO日期");
			return LocalDate.parse(v);
		} catch (java.time.DateTimeException ex) {
			throw new CheckedException("日期须真实ISO日期");
		}
	}

	private String text(JsonNode n, String k, boolean nullable, int max) {
		JsonNode v = n.get(k);
		require(v != null && ((nullable && v.isNull()) || v.isTextual()), "字段文本类型无效：" + k);
		if (v.isNull()) {
			return null;
		}
		String s = v.textValue();
		require(s.length() <= max && (nullable || !s.isBlank()) && s.codePoints().noneMatch(Character::isISOControl),
				"字段长度或字符无效：" + k);
		require(s.chars().noneMatch(c -> Character.isSurrogate((char) c)) || validSurrogates(s), "文本包含无效Unicode");
		return s;
	}

	private boolean validSurrogates(String s) {
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (Character.isHighSurrogate(c)) {
				if (++i >= s.length() || !Character.isLowSurrogate(s.charAt(i))) {
					return false;
				}
			} else if (Character.isLowSurrogate(c)) {
				return false;
			}
		}
		return true;
	}

	private void fields(JsonNode n, String... keys) {
		require(n != null && n.isObject(), "协议对象缺失");
		Set<String> names = new HashSet<>();
		n.fieldNames().forEachRemaining(names::add);
		require(names.equals(Set.of(keys)), "协议字段缺失或含未知字段");
	}

	private <T> T convert(JsonNode n, Class<T> type) {
		try {
			return json.treeToValue(n, type);
		} catch (java.io.IOException ex) {
			throw new CheckedException("协议类型无效");
		}
	}

	private String write(JsonNode n) {
		try {
			return json.writeValueAsString(n);
		} catch (java.io.IOException ex) {
			throw new CheckedException("规范命令生成失败");
		}
	}
}
