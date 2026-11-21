package com.kitex.mom.mes.util;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mes.entity.MesAutoReportRuleEntity;
import com.kitex.mom.mes.entity.MesProduceTaskEntity;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.enums.WorksiteTypeEnum;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/** 根据工序和派工冻结的候选工位、触发方式及实际开工工位判定报工入口。 */
public final class MesWorksiteTriggerProjection {

	private MesWorksiteTriggerProjection() {
	}

	public static String forDispatch(String sourceCodes, String sourceTypes, List<String> selected) {
		List<String> codes = stationCodes(sourceCodes);
		if (selected == null || selected.isEmpty() || selected.stream().anyMatch(Objects::isNull)
				|| new HashSet<>(selected).size() != selected.size() || !codes.containsAll(selected)) {
			throw new CheckedException("派工工位与原工序候选工位不一致，请核对配置");
		}
		List<WorksiteTypeEnum> triggers = triggers(sourceTypes, codes.size());
		return selected.stream().map(code -> triggers.get(codes.indexOf(code)).name())
				.collect(Collectors.joining(","));
	}

	public static WorksiteTypeEnum forExecution(MesProduceTaskEntity parent, MesTaskArrangeEntity arrange) {
		if (arrange == null) {
			throw new CheckedException("派工任务不存在，无法核对报工触发方式");
		}
		String raw = arrange.getWorksiteTypes();
		if (raw == null || raw.isBlank()) {
			return WorksiteTypeEnum.MANUAL;
		}
		// A single valid type remains compatible with legacy uniform station settings.
		if (!raw.contains(",") && isTrigger(raw)) {
			return WorksiteTypeEnum.fromCode(raw);
		}
		List<String> codes = stationCodes(arrange.getWorksiteCodes());
		List<String> values = csv(raw);
		if (values.equals(codes)
				&& (arrange.getActualWorksiteCode() == null || codes.contains(arrange.getActualWorksiteCode()))
				&& sameOriginalParent(parent, arrange)
				&& (parent.getWorksiteTypes() == null || parent.getWorksiteTypes().isBlank())
				&& stationCodes(parent.getWorksiteCodes()).containsAll(codes)) {
			// The old dispatcher copied station identifiers into an absent trigger field.
			// Only this exact provenance uses the pre-existing MANUAL default; no row is rewritten.
			return WorksiteTypeEnum.MANUAL;
		}
		List<WorksiteTypeEnum> mapped = triggers(raw, codes.size());
		if (mapped.stream().distinct().count() == 1) {
			return mapped.get(0);
		}
		int index = codes.indexOf(arrange.getActualWorksiteCode());
		if (index < 0) {
			throw new CheckedException("候选工位的报工方式不同，请先确认实际开工工位");
		}
		return mapped.get(index);
	}

	/**
	 * 判断设备规则是否适用当前实际工位。已开工派工按接单时确认的工位匹配；
	 * 多个候选工位分别配置触发方式时，使用实际工位对应的那一项。
	 * 历史单值触发配置可缺候选清单，但必须有实际工位。
	 *
	 * @param arrange 待接收设备事件的派工
	 * @param rule 当前设备规则，工序、工位限定可空
	 * @return 实际工位允许自动报工且满足规则限定时为 true
	 */
	public static boolean allowsDeviceRule(MesTaskArrangeEntity arrange, MesAutoReportRuleEntity rule) {
		String actual = arrange.getActualWorksiteCode();
		if (actual == null || actual.isBlank()
				|| !matchesLimit(rule.getRouteCode(), arrange.getRouteCode())
				|| !matchesLimit(rule.getWorksiteCode(), actual)) {
			return false;
		}
		String candidates = arrange.getWorksiteCodes();
		if (candidates != null && !candidates.isBlank() && !stationCodes(candidates).contains(actual)) {
			return false;
		}
		return forExecution(null, arrange) == WorksiteTypeEnum.AUTO;
	}

	private static boolean matchesLimit(String limit, String actual) {
		return limit == null || limit.isBlank() || limit.equals(actual);
	}

	private static List<WorksiteTypeEnum> triggers(String raw, int count) {
		if (raw == null || raw.isBlank()) {
			return Collections.nCopies(count, WorksiteTypeEnum.MANUAL);
		}
		List<String> values = csv(raw);
		if (values.size() != 1 && values.size() != count) {
			throw new CheckedException("工位与报工触发方式的配置数量不一致，请核对原工序配置");
		}
		if (values.stream().anyMatch(value -> !isTrigger(value))) {
			throw new CheckedException("工序报工触发方式无效，请核对原工序配置");
		}
		if (values.size() == 1) {
			return Collections.nCopies(count, WorksiteTypeEnum.fromCode(values.get(0)));
		}
		return values.stream().map(WorksiteTypeEnum::fromCode).collect(Collectors.toList());
	}

	private static List<String> stationCodes(String raw) {
		List<String> codes = csv(raw);
		if (codes.isEmpty() || codes.stream().anyMatch(String::isBlank)
				|| new HashSet<>(codes).size() != codes.size()) {
			throw new CheckedException("原工序候选工位缺失或重复，请核对配置");
		}
		return codes;
	}

	private static List<String> csv(String raw) {
		return raw == null ? List.of() : Arrays.stream(raw.split(",", -1))
				.map(String::trim).collect(Collectors.toList());
	}

	private static boolean isTrigger(String value) {
		return "MANUAL".equalsIgnoreCase(value.trim()) || "AUTO".equalsIgnoreCase(value.trim());
	}

	private static boolean text(String value) {
		return value != null && !value.isBlank() && value.equals(value.strip());
	}

	private static boolean sameOriginalParent(MesProduceTaskEntity parent, MesTaskArrangeEntity arrange) {
		return parent != null && parent.getTenantId() != null && text(parent.getFactoryCode())
				&& text(parent.getTaskCode()) && text(parent.getCode())
				&& text(parent.getSourceRouteNodeCode())
				&& Objects.equals(parent.getTenantId(), arrange.getTenantId())
				&& Objects.equals(parent.getFactoryCode(), arrange.getFactoryCode())
				&& Objects.equals(parent.getTaskCode(), arrange.getTaskCode())
				&& Objects.equals(parent.getCode(), arrange.getProduceTaskCode())
				&& Objects.equals(parent.getSourceRouteNodeCode(), arrange.getSourceRouteNodeCode());
	}
}
