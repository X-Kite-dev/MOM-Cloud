package com.kitex.mom.mes.util;

import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mes.dto.MdmBomChildListDTO;
import com.kitex.mom.mes.entity.MesWarehouseFeedEntity;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 只从冻结BOM和原投料记录派生展示数量，不改写快照、投料或库存事实。
 * 每个节点的需求量按父需求逐层展开；投料及已用量是该需求物料的整工单当量。
 * 同物料出现在多条路径时显示相同物料汇总，不推断路径分摊，也不向父物料累计。
 */
public final class MesFrozenBomProjection {

	private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
			.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
			.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
			.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

	private MesFrozenBomProjection() {
	}

	/**
	 * 调用方须先按真实租户、工厂和工单读取投料全集。六位当量仅供展示，不用于执行扣料。
	 * 旧主料缺失绑定与比例时可按原物料和1换算；未知数量或未知替代关系不可显示为零。
	 */
	public static List<MdmBomChildListDTO> project(String snapshot, BigDecimal taskNoticeNum,
			List<MesWarehouseFeedEntity> feedList) {
		require(taskNoticeNum != null && taskNoticeNum.signum() > 0, "工单计划数量未知或非正数，无法计算BOM需求");
		require(feedList != null, "投料记录未知，不能按没有投料展示");
		List<MdmBomChildListDTO> roots = readSnapshot(snapshot);
		Set<String> materials = new HashSet<>();
		for (var root : roots) {
			populateDemand(root, taskNoticeNum, materials);
		}

		Map<String, Map<BigDecimal, Amounts>> byMaterial = new HashMap<>();
		for (var feed : feedList) {
			require(feed != null, "投料记录含空来源，无法汇总");
			String source = "投料[" + feed.getId() + "]";
			String actual = feed.getMatCode();
			require(text(actual), source + "缺少实际物料编码");
			String requirement = feed.getRequirementMatCode();
			if (requirement == null || requirement.isBlank()) {
				require(!Boolean.TRUE.equals(feed.getSubstituteFlag()), source + "替代料缺少原需求物料");
				require(feed.getSubstituteRatio() == null
						|| feed.getSubstituteRatio().compareTo(BigDecimal.ONE) == 0,
						source + "缺少可解释的原需求绑定");
				requirement = actual;
			}
			require(text(requirement) && materials.contains(requirement), source + "需求物料不属于冻结BOM");
			boolean main = requirement.equals(actual);
			require(main ? !Boolean.TRUE.equals(feed.getSubstituteFlag())
					: Boolean.TRUE.equals(feed.getSubstituteFlag()), source + "主料替代标识与原需求绑定矛盾或未知");
			BigDecimal ratio = feed.getSubstituteRatio();
			if (ratio == null && main) {
				ratio = BigDecimal.ONE;
			}
			require(ratio != null && ratio.signum() > 0, source + "原替代比例未知或非正数");
			require(!main || ratio.compareTo(BigDecimal.ONE) == 0, source + "主料比例不是1，无法解释当量");
			BigDecimal fed = feed.getFeedNum();
			BigDecimal used = feed.getUsedNum();
			require(fed != null && used != null && fed.signum() >= 0 && used.signum() >= 0
					&& used.compareTo(fed) <= 0, source + "投料量或已用量未知、负数或超出原投料");
			Map<BigDecimal, Amounts> ratios = byMaterial.computeIfAbsent(requirement, ignored -> new HashMap<>());
			BigDecimal key = ratio.stripTrailingZeros();
			Amounts before = ratios.getOrDefault(key, new Amounts(BigDecimal.ZERO, BigDecimal.ZERO));
			ratios.put(key, new Amounts(before.fed().add(fed), before.used().add(used)));
		}

		Map<String, Amounts> totals = new HashMap<>();
		for (var material : byMaterial.entrySet()) {
			BigDecimal fed = BigDecimal.ZERO;
			BigDecimal used = BigDecimal.ZERO;
			for (var ratio : material.getValue().entrySet()) {
				fed = fed.add(ratio.getValue().fed().divide(ratio.getKey(), MathContext.DECIMAL128));
				used = used.add(ratio.getValue().used().divide(ratio.getKey(), MathContext.DECIMAL128));
			}
			totals.put(material.getKey(), new Amounts(fed.setScale(6, RoundingMode.HALF_UP),
					used.setScale(6, RoundingMode.HALF_UP)));
		}
		for (var root : roots) {
			populateFeed(root, totals);
		}
		return roots;
	}

	private static List<MdmBomChildListDTO> readSnapshot(String snapshot) {
		require(snapshot != null && !snapshot.isBlank(), "生产工单缺少冻结BOM快照");
		try {
			JsonNode root = JSON.readTree(snapshot);
			require(root != null && root.isArray(), "生产工单冻结BOM快照必须是数组");
			validateStructure(root, 0);
			return JSONUtil.toList(root.toString(), MdmBomChildListDTO.class);
		} catch (CheckedException error) {
			throw error;
		} catch (Exception error) {
			throw new CheckedException("生产工单冻结BOM快照无法完整解析");
		}
	}

	private static void validateStructure(JsonNode rows, int depth) {
		require(depth < 64, "冻结BOM超过64层，无法完整展示");
		for (var row : rows) {
			require(row.isObject(), "冻结BOM包含空行或非对象节点");
			JsonNode code = row.get("materialCode");
			JsonNode quantity = row.get("quantity");
			require(code != null && code.isTextual() && text(code.textValue()), "冻结BOM缺少需求物料编码");
			require(quantity != null && (quantity.isNumber() || quantity.isTextual()),
					"BOM物料[" + code.textValue() + "]单位用量未知");
			JsonNode children = row.get("children");
			if (children != null && !children.isNull()) {
				require(children.isArray(), "BOM物料[" + code.textValue() + "]子结构不是数组");
				validateStructure(children, depth + 1);
			}
		}
	}

	private static void populateDemand(MdmBomChildListDTO node, BigDecimal parentNeedNum, Set<String> materials) {
		require(node != null && node.getQuantity() != null && node.getQuantity().signum() > 0,
				"冻结BOM单位用量未知或非正数");
		BigDecimal need = parentNeedNum.multiply(node.getQuantity());
		node.setNoticeNum(parentNeedNum);
		node.setNeedNum(need);
		materials.add(node.getMaterialCode());
		if (node.getChildren() != null) {
			for (var child : node.getChildren()) {
				populateDemand(child, need, materials);
			}
		}
	}

	private static void populateFeed(MdmBomChildListDTO node, Map<String, Amounts> totals) {
		Amounts amount = totals.getOrDefault(node.getMaterialCode(), new Amounts(BigDecimal.ZERO, BigDecimal.ZERO));
		node.setFeedNum(amount.fed());
		node.setUsedNum(amount.used());
		if (node.getChildren() != null) {
			for (var child : node.getChildren()) {
				populateFeed(child, totals);
			}
		}
	}

	private static boolean text(String value) {
		return value != null && !value.isBlank() && value.equals(value.strip());
	}

	private static void require(boolean condition, String message) {
		if (!condition) {
			throw new CheckedException(message);
		}
	}

	private record Amounts(BigDecimal fed, BigDecimal used) {
	}
}
