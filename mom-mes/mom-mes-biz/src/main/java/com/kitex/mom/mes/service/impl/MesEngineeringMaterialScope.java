package com.kitex.mom.mes.service.impl;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mes.dto.MesEngineeringMaterialScopeDTO.*;
import com.kitex.mom.mes.dto.MesInFlightEngineeringImpactDTO.PackageRef;
import com.kitex.mom.mes.entity.MesTaskEntity;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 从两份已核验工程原件生成材料协作范围，不访问今日主数据或执行替代解析器。
 * 原BOM路径、未引用材料及全部替代声明都保留；不能据此宣称库存无占用或允许换版。
 */
@Component
public class MesEngineeringMaterialScope {

	private static final ObjectMapper JSON = new ObjectMapper()
			.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
			.enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
			.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
			.setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));

	/**
	 * 保留确定字段顺序的原范围正文及SHA。任何一侧提取不完整时返回明确待核，
	 * 不影响原通用工程差异阅读，也不把部分材料列表交给权威域当作完整范围。
	 */
	public Scope project(MesTaskEntity task, JsonNode before, JsonNode after, PackageRef source, PackageRef target) {
		try {
			require(task.getNoticeNum() != null && task.getNoticeNum().signum() > 0,
					"工单总需求缺失或非正数，材料协作范围待核");
			var rows = new ArrayList<Row>();
			var codes = new TreeSet<String>();
			var nodes = new TreeSet<String>();
			codes.add(task.getMatCode());
			var body = JSON.createObjectNode();
			body.put("schemaVersion", "MES_ENGINEERING_MATERIAL_SCOPE_V1");
			body.put("tenantId", task.getTenantId().toString());
			body.put("factoryCode", task.getFactoryCode());
			body.put("taskId", task.getId().toString());
			body.put("taskCode", task.getCode());
			body.put("productCode", task.getMatCode());
			// noticeNum是工单总需求，不冒称已冻结单位，也不乘出跨层需求量。
			body.put("noticeNum", task.getNoticeNum().toPlainString());
			body.set("source", reference(source));
			body.set("target", reference(target));
			body.set("before", slice(task, "SOURCE", before, rows, codes, nodes));
			body.set("after", slice(task, "TARGET", after, rows, codes, nodes));
			body.set("routeNodeCodes", JSON.valueToTree(nodes));
			body.set("materialCodes", JSON.valueToTree(codes));
			String raw = body.toString();
			require(raw.getBytes(StandardCharsets.UTF_8).length <= 32 * 1024 * 1024, "材料范围原文超过32MiB，不能截断");
			return new Scope("COMPLETE", List.of(), digest(raw), raw, List.copyOf(rows), List.copyOf(codes));
		} catch (CheckedException failure) {
			return new Scope("UNAVAILABLE", List.of(failure.getMessage()), null, null, List.of(), List.of());
		}
	}

	/**
	 * 两侧范围固定保留完整BOM头/来源树、节点配置/设置及材料声明，不按是否变化缩小。
	 */
	private ObjectNode slice(MesTaskEntity task, String side, JsonNode root, List<Row> rows, Set<String> codes,
	                         Set<String> nodeCodes) {
		JsonNode bom = root.path("bom");
		var sources = new LinkedHashMap<String, JsonNode>();
		require(bom.isObject() && root.path("bomStructure").path("sources").isArray(), "BOM版本头或完整来源缺失");
		for (JsonNode header : root.path("bomStructure").path("sources")) {
			organization(task, header);
			String bomId = id(header, "id");
			require(sources.putIfAbsent(bomId, header) == null && sources.size() <= 10001, "BOM来源重复或超限");
			codes.add(text(header, "productCode"));
		}
		String rootId = id(bom, "id");
		require(bom.equals(sources.get(rootId)) && task.getMatCode().equals(text(bom, "productCode")),
				"根BOM来源头或工单产品不一致");
		var usedVersions = new HashSet<>(Set.of(rootId));
		bom(task, side, root.path("bomStructure").path("children"), rootId, "/bomStructure/children",
				sources, new HashSet<>(Set.of(rootId)), usedVersions, rows, codes, new int[1]);
		require(usedVersions.equals(sources.keySet()), "BOM来源含未被完整子树引用的版本，范围待核");
		String routeCode = text(root.path("route"), "routeCode");
		var value = JSON.createObjectNode();
		value.set("bom", bom);
		value.set("bomStructure", root.path("bomStructure"));
		var frozenNodes = value.putArray("nodes");
		require(root.path("nodes").isArray() && !root.path("nodes").isEmpty() && root.path("nodes").size() <= 200,
				"稳定节点范围缺失或超限");
		var uniqueNodes = new HashSet<String>();
		var materialPresence = new LinkedHashMap<String, Boolean>();
		for (int index = 0; index < root.path("nodes").size(); index++) {
			JsonNode node = root.path("nodes").get(index);
			String rn = text(node, "routeNodeCode");
			require(rn.matches("RN-[1-9][0-9]{0,18}") && uniqueNodes.add(rn), "稳定节点缺失或重复");
			nodeCodes.add(rn);
			String process = text(node, "processCode");
			require(node.has("setting"), "节点设置字段缺失，不能以未知代替不适用");
			if (node.hasNonNull("setting")) {
				nodeSource(task, node.get("setting"), routeCode, rn, process);
			}
			require(node.path("materials").isArray() && node.path("materials").size() <= 10000,
					"节点材料清单缺失或超限");
			materialPresence.put(rn, !node.path("materials").isEmpty());
			var projection = frozenNodes.addObject();
			for (String field : List.of("routeNodeCode", "processCode", "processName", "materials", "setting")) {
				if (node.has(field)) {
					projection.set(field, node.get(field));
				}
			}
			var uniqueMaterials = new HashSet<String>();
			for (int item = 0; item < node.path("materials").size(); item++) {
				JsonNode material = node.path("materials").get(item);
				nodeSource(task, material, routeCode, rn, process);
				require(uniqueMaterials.add(id(material, "id")) && rn.equals(text(material, "routeNodeCode"))
						&& process.equals(text(material, "processCode")), "节点用料身份重复或归属不符");
				add(side, "NODE", "/nodes/" + index + "/materials/" + item, material, null, null, rn, process, rows,
						codes);
			}
		}
		require(root.path("declarations").isArray(), "原材料适用声明清单缺失");
		var declarations = value.putArray("declarations");
		var declared = new HashSet<String>();
		for (JsonNode declaration : root.path("declarations")) {
			if ("MATERIAL".equals(declaration.path("category").asText())) {
				String rn = text(declaration, "routeNodeCode");
				String mode = text(declaration, "mode");
				String reason = optional(declaration, "reason");
				require(uniqueNodes.contains(rn) && declared.add(rn) &&
								Set.of("REQUIRED", "NOT_APPLICABLE").contains(mode),
						"材料声明缺真实节点、重复或模式未知");
				require(reason != null && reason.length() <= 1000 && reason.indexOf('\0') < 0
								&& ("REQUIRED".equals(mode) || !reason.isBlank())
								&& materialPresence.get(rn) == "REQUIRED".equals(mode),
						"材料声明依据或实际配置与原发布契约不符");
				declarations.add(declaration);
			}
		}
		require(declared.equals(uniqueNodes), "存在稳定节点缺少唯一材料适用声明");
		return value;
	}

	/**
	 * 逐真实路径递归，允许同物料和同子版本在不同路径出现，拒绝循环/缺子树及超量。
	 */
	private void bom(MesTaskEntity task, String side, JsonNode children, String parentId, String path,
	                 Map<String, JsonNode> sources, Set<String> ancestors, Set<String> usedVersions, List<Row> rows,
	                 Set<String> codes,
	                 int[] count) {
		require(children.isArray() && ancestors.size() <= 64, "完整BOM子树缺失或超过64层");
		var unique = new HashSet<String>();
		for (int index = 0; index < children.size(); index++) {
			JsonNode child = children.get(index);
			organization(task, child);
			require(++count[0] <= 10000 && unique.add(id(child, "id")) && parentId.equals(id(child, "bomId")),
					"BOM明细超限、重复或父版本归属不符");
			String childPath = path + "/" + index;
			String sub = child.hasNonNull("subitemBomId") ? idOrZero(child.get("subitemBomId")) : null;
			if ("0".equals(sub)) {
				sub = null;
			}
			add(side, "BOM", childPath, child, parentId, sub, null, null, rows, codes);
			if (sub != null) {
				require(sources.containsKey(sub) && ancestors.add(sub)
								&& text(child, "materialCode").equals(text(sources.get(sub), "productCode")),
						"子BOM缺失、产品不符或循环引用");
				usedVersions.add(sub);
				bom(task, side, child.path("children"), sub, childPath + "/children", sources, ancestors, usedVersions,
						rows, codes,
						count);
				ancestors.remove(sub);
			} else {
				require(!child.hasNonNull("children") ||
								child.path("children").isArray() && child.path("children").isEmpty(),
						"未引用子BOM却含未知子层内容");
			}
		}
	}

	/**
	 * 原数量只作十进制JSON展示；BOM子层在独立行保留，不二次展开到父行。
	 */
	private void add(String side, String kind, String path, JsonNode original, String bomId, String subId,
	                 String rn, String process, List<Row> rows, Set<String> codes) {
		require(original.isObject() && rows.size() < 40000 && original.path("quantity").isNumber()
				&& original.path("quantity").decimalValue().signum() > 0, "用料数量缺失、非正数或材料行超限");
		String code = text(original, "materialCode");
		codes.add(code);
		// 只采当前行字段，不先复制整棵子树再删除；深层重复子BOM不产生二次复制放大。
		var row = JSON.createObjectNode();
		original.fields().forEachRemaining(field -> {
			if (!"children".equals(field.getKey())) {
				row.set(field.getKey(), field.getValue());
			}
		});
		rows.add(new Row(side, kind, path, id(original, "id"), bomId, subId, rn, process, code,
				optional(original, "materialName"), original.get("quantity").toString(), text(original, "unit"),
				substitutes(original, codes), row.toString()));
	}

	/**
	 * 原结构化声明和兼容编码都进入保守范围，保留原优先级/日期/认证，不做资格或比例推算。
	 */
	private List<Substitute> substitutes(JsonNode row, Set<String> codes) {
		var result = new ArrayList<Substitute>();
		String policy = optional(row, "substitutePolicy");
		if (policy != null && !policy.isBlank()) {
			JsonNode options;
			try {
				options = JSON.readTree(policy);
			} catch (Exception failure) {
				throw new CheckedException("结构化替代声明JSON无法完整解析");
			}
			require(options != null && options.isArray() && options.size() <= 10000,
					"结构化替代声明不是完整数组或超限");
			for (int index = 0; index < options.size(); index++) {
				JsonNode option = options.get(index);
				String code = text(option, "substituteCode");
				codes.add(code);
				result.add(new Substitute("STRUCTURED", "/substitutePolicy/" + index, code,
						optional(option, "substituteName"),
						optional(option, "substituteUnit"), raw(option, "substituteRatio"), raw(option, "enabled"),
						optional(option, "effectiveStart"), optional(option, "effectiveEnd"), option.toString()));
			}
		}
		String legacy = optional(row, "replaceCodes");
		if (legacy != null && !legacy.isBlank()) {
			String[] values = legacy.split(",", -1);
			require(values.length <= 10000, "兼容替代料声明超限");
			for (int index = 0; index < values.length; index++) {
				String code = values[index].trim();
				require(!code.isBlank(), "兼容替代料编码不完整");
				codes.add(code);
				result.add(new Substitute("LEGACY", "/replaceCodes/" + index, code, null, null, null, null, null, null,
						JSON.getNodeFactory().textNode(values[index]).toString()));
			}
		}
		return List.copyOf(result);
	}

	/**
	 * 原版本对以固定字段顺序参与派生范围hash；不以显示版本号替代真实发布身份。
	 */
	private ObjectNode reference(PackageRef reference) {
		var value = JSON.createObjectNode();
		value.put("releaseId", reference.releaseId());
		value.put("contentHash", reference.contentHash());
		value.put("routeId", reference.routeId());
		value.put("bomId", reference.bomId());
		value.put("versionNo", reference.versionNo());
		return value;
	}

	/**
	 * 所有来源行须处于实际工单组织，不能用请求范围掩盖串组织原件。
	 */
	private void organization(MesTaskEntity task, JsonNode row) {
		require(task.getTenantId().toString().equals(id(row, "tenantId"))
				&& task.getFactoryCode().equals(text(row, "factoryCode")), "材料原件租户或工厂与工单不一致");
	}

	/**
	 * 节点设置与逐料行都须绑定当前路线/RN/工序，不将错误类型或其他组织原件视作完整。
	 */
	private void nodeSource(MesTaskEntity task, JsonNode row, String route, String rn, String process) {
		require(row.isObject(), "节点设置或用料不是完整对象");
		organization(task, row);
		id(row, "id");
		require(route.equals(text(row, "routeCode")) && rn.equals(text(row, "routeNodeCode"))
				&& process.equals(text(row, "processCode")), "节点设置或用料的路线/节点/工序归属不符");
	}

	/**
	 * 身份保持十进制文本，不经浮点解析；合法子版本空值由调用方单独判断。
	 */
	private String id(JsonNode row, String field) {
		String value = idOrZero(row.path(field));
		require(!"0".equals(value), "材料身份字段" + field + "缺失或无效");
		return value;
	}

	/**
	 * 保留原子BOM的0占位，不接受数值指数、负号或浏览器精度修正。
	 */
	private String idOrZero(JsonNode value) {
		require((value.isTextual() || value.isIntegralNumber()) && value.asText().matches("0|[1-9][0-9]{0,18}"),
				"材料来源ID格式不完整");
		return value.asText();
	}

	/**
	 * 必填编码/单位按原文保留，不补默认值或裁剪有效内容。
	 */
	private String text(JsonNode row, String field) {
		String value = optional(row, field);
		require(value != null && !value.isBlank(), "材料字段" + field + "缺失");
		return value;
	}

	/**
	 * 可空文本保持null；非文本形状须待核，不能静默asText吞掉损坏字段。
	 */
	private String optional(JsonNode row, String field) {
		if (!row.hasNonNull(field)) {
			return null;
		}
		require(row.get(field).isTextual(), "材料字段" + field + "不是原文本");
		return row.get(field).textValue();
	}

	/**
	 * 原JSON值含显式null；缺字段才返回null，不将未知数量显示成0。
	 */
	private String raw(JsonNode row, String field) {
		return row.has(field) ? row.get(field).toString() : null;
	}

	/**
	 * hash只绑定本次派生材料范围，不构造提供方证据或有效期。
	 */
	private String digest(String raw) {
		try {
			return HexFormat.of()
					.formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception failure) {
			throw new IllegalStateException("材料范围摘要计算失败", failure);
		}
	}

	/**
	 * 明确拒绝不完整原件，交由入口显示待核，禁止返回缩小范围的成功结果。
	 */
	private void require(boolean valid, String message) {
		if (!valid) {
			throw new CheckedException(message);
		}
	}
}
