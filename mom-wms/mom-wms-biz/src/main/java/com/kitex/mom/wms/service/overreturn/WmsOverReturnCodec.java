package com.kitex.mom.wms.service.overreturn;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.kitex.mom.wms.dto.WmsOverReturnDTO.*;
import com.kitex.mom.wms.entity.WmsInoutFlowEntity;
import org.springframework.stereotype.Component;

import java.beans.Introspector;
import java.math.BigDecimal;
import java.util.*;

import static com.kitex.mom.wms.service.overreturn.WmsOverReturnRules.*;

/**
 * 严格全文解析与完整补扣快照；数字身份和数量永不经JSON number传输。
 */
@Component
public class WmsOverReturnCodec {
	private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
			.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
			.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
			.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
			.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

	/**
	 * 序列化冻结协议，保留显式空字段。
	 */
	public String encode(Object value) {
		try {
			return json.writeValueAsString(value);
		} catch (Exception e) {
			throw new IllegalArgumentException("返还证据无法序列化", e);
		}
	}

	/**
	 * 严格解析原封命令，拒重复键、尾随内容、未知或缺失字段。
	 */
	public Command command(String value) {
		require(value != null && value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 262144 &&
				value.indexOf(0) < 0, "原请求全文无效或超限");
		try {
			JsonNode node = json.readTree(value);
			noNul(node);
			keys(node, Set.of("v", "caseId", "warehouseCode", "evidence", "lines"));
			require(node.get("v").isIntegralNumber() && node.get("v").intValue() == 1, "原请求版本无效");
			for (String key : List.of("caseId", "warehouseCode", "evidence")) {
				require(node.get(key).isTextual(), "命令字段类型无效");
			}
			require(node.get("lines").isArray() && !node.get("lines").isEmpty() && node.get("lines").size() <= 500,
					"请选择本次实收片段，最多500条");
			Set<String> seen = new HashSet<>();
			for (JsonNode line : node.get("lines")) {
				keys(line, Set.of("adjustmentFlowId", "quantity", "unit", "barcode", "siteCode", "containerCode"));
				for (String key : List.of("adjustmentFlowId", "quantity", "unit", "siteCode")) {
					require(line.get(key).isTextual(), "片段字段必须为文本");
				}
				for (String key : List.of("barcode", "containerCode")) {
					require(line.get(key).isNull() || line.get(key).isTextual(), "可空字段须显式null或文本");
				}
				String flow = line.get("adjustmentFlowId").textValue();
				id(flow);
				require(seen.add(flow), "同批补扣片段不能重复");
				quantity(line.get("quantity").textValue());
			}
			Command result = json.treeToValue(node, Command.class);
			id(result.caseId());
			text(result.warehouseCode(), 100, "原仓");
			text(result.evidence(), 1000, "签收证据");
			return result;
		} catch (com.kitex.common.core.exception.CheckedException e) {
			throw e;
		} catch (Exception e) {
			throw new IllegalArgumentException("返还全文结构或字段类型无效", e);
		}
	}

	/**
	 * 转义后的NUL也属于非法文本，不只检查原始JSON字节。
	 */
	private void noNul(JsonNode node) {
		if (node.isTextual()) {
			require(node.textValue().indexOf(0) < 0, "命令文本含无效NUL");
		}
		if (node.isContainerNode()) {
			node.elements().forEachRemaining(this::noNul);
		}
	}

	private void keys(JsonNode node, Set<String> expected) {
		Set<String> actual = new HashSet<>();
		node.fieldNames().forEachRemaining(actual::add);
		require(node.isObject() && actual.equals(expected), "命令字段缺失或存在未知字段");
	}

	/**
	 * 所有bean属性逐项冻结，原Double须与数据库decimal另行核验；库龄缺失禁止冻结。
	 */
	public String snapshot(WmsInoutFlowEntity flow) {
		require(flow.getStockInTime() != null && flow.getMatNum() != null && Double.isFinite(flow.getMatNum()),
				"补扣缺少原库存库龄或有效数量");
		ObjectNode node = json.valueToTree(flow);
		try {
			for (var p : Introspector.getBeanInfo(WmsInoutFlowEntity.class, Object.class).getPropertyDescriptors()) {
				if (p.getReadMethod() == null || p.getWriteMethod() == null) {
					continue;
				}
				Object v = p.getReadMethod().invoke(flow);
				if (v == null) {
					node.putNull(p.getName());
				} else if (v instanceof Long) {
					node.put(p.getName(), v.toString());
				} else if (v instanceof Double d) {
					node.put(p.getName(), decimal(BigDecimal.valueOf(d)));
				}
			}
		} catch (Exception e) {
			throw new IllegalArgumentException("补扣原件无法冻结", e);
		}
		quantity(node.get("matNum").textValue());
		return encode(node);
	}

	/**
	 * 验证并恢复完整补扣原件快照；拒绝字段类型漂移或缺失。
	 */
	public WmsInoutFlowEntity source(String value) {
		try {
			WmsInoutFlowEntity source = json.readValue(value, WmsInoutFlowEntity.class);
			require(snapshot(source).equals(value), "原件快照类型或完整性无效");
			return source;
		} catch (Exception e) {
			throw new IllegalArgumentException("原件快照不完整", e);
		}
	}

	/**
	 * 解析永久效果，坏包明确报错。
	 */
	public List<Effect> effects(String value) {
		try {
			return json.readValue(value, json.getTypeFactory().constructCollectionType(List.class, Effect.class));
		} catch (Exception e) {
			throw new IllegalArgumentException("永久返还回执不完整", e);
		}
	}
}
