package com.kitex.mom.mes.service.impl;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mes.dto.MesEquipmentOeeDTO.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;

/**
 * 按单设备、产品、稳定节点及原单位纯计算OEE，不连接当前人工计时或质量配置。
 */
public class MesEquipmentOeeCalculator {
	private static final ObjectMapper JSON = json();
	public static final String SOURCE = "MES_EQUIPMENT_OEE_SOURCE_V1";
	private static final BigDecimal HUNDRED = new BigDecimal("100");

	private static ObjectMapper json() {
		ObjectMapper mapper = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
				.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
				.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
		for (CoercionInputShape shape : List.of(CoercionInputShape.Integer, CoercionInputShape.Float,
				CoercionInputShape.Boolean, CoercionInputShape.EmptyString)) {
			mapper.coercionConfigFor(LogicalType.Textual).setCoercion(shape, CoercionAction.Fail);
		}
		mapper.coercionConfigFor(LogicalType.Boolean).setCoercion(CoercionInputShape.String, CoercionAction.Fail)
				.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail);
		return mapper;
	}

	/**
	 * 严格解析一个OEE来源原文；未知字段、重复键、隐式数字转文本或非法JSON拒绝。
	 */
	public Batch decode(String raw) {
		require(raw != null && raw.getBytes(StandardCharsets.UTF_8).length <= 1_048_576,
				"OEE来源文件为空或超过1MiB");
		try {
			return JSON.readValue(raw, Batch.class);
		} catch (Exception failure) {
			throw new CheckedException("OEE来源协议、字段类型或JSON原文无效", failure);
		}
	}

	/**
	 * 按固定DTO协议编码来源内容，用于同号同事实比较；原始导入字符串仍单独冻结。
	 */
	public String canonical(Batch batch) {
		try {
			return JSON.writeValueAsString(batch);
		} catch (Exception failure) {
			throw new CheckedException("OEE来源无法序列化", failure);
		}
	}

	/**
	 * 按指定UTC观察时刻计算单设备/产品/节点/单位的来源；矛盾事实拒绝，缺口保留为空而非零。
	 */
	public Measurement measure(String raw, Instant observed) {
		Batch batch = decode(raw);
		require(batch != null && SOURCE.equals(batch.contract()), "不支持的OEE来源协议");
		text(batch.batchId(), "来源批次号");
		text(batch.deviceCode(), "设备编码");
		text(batch.productCode(), "产品编码");
		text(batch.routeNodeCode(), "稳定工艺节点");
		text(batch.unit(), "原计量单位");
		text(batch.providerCode(), "来源提供方");
		text(batch.originReference(), "原始证据引用");
		text(batch.originTimezone(), "来源时区");
		try {
			ZoneId.of(batch.originTimezone());
		} catch (Exception failure) {
			throw new CheckedException("来源时区必须是有效IANA时区", failure);
		}
		require(Set.of("SOURCE_IMPORT", "SIMULATION").contains(Objects.toString(batch.sourceKind(), "")),
				"来源只能为人工确认导入或模拟");
		require(("SOURCE_IMPORT".equals(batch.sourceKind()) ? "OPERATOR_CONFIRMED" : "SIMULATED")
				.equals(batch.confirmation()), "导入确认与来源类型不一致，不能冒充真实设备认证");
		Instant start = time(batch.periodStart());
		Instant end = time(batch.periodEnd());
		require(end.isAfter(start) && observed != null && !end.isAfter(observed)
						&& Duration.between(start, end).compareTo(Duration.ofDays(31)) <= 0,
				"OEE周期须已结束、正向且不超过31天，时间使用UTC Z格式");
		require(batch.plannedWindows() != null && batch.observations() != null && batch.outputs() != null,
				"必须明确提供计划窗、运行观察和产出数组，空数组不能省略");
		require(batch.plannedWindows().size() <= 1000 && batch.observations().size() <= 10000
				&& batch.outputs().size() <= 10000, "OEE来源超过单批有界数量，请拆分周期");
		List<Span> planned = new ArrayList<>();
		Set<String> ids = new HashSet<>();
		for (Window row : batch.plannedWindows()) {
			require(row != null, "计划窗不能为空");
			unique(ids, row.sourceId());
			planned.add(span(row.start(), row.end(), start, end, "PLANNED"));
		}
		ordered(planned);
		List<Span> observations = new ArrayList<>();
		ids.clear();
		for (Observation row : batch.observations()) {
			require(row != null, "运行观察不能为空");
			unique(ids, row.sourceId());
			require(Set.of("RUN", "STOP", "UNKNOWN").contains(Objects.toString(row.state(), "")),
					"观察状态须为RUN、STOP或UNKNOWN");
			observations.add(span(row.start(), row.end(), start, end, row.state()));
		}
		ordered(observations);
		Set<String> gaps = new LinkedHashSet<>();
		boolean calendarKnown = Boolean.TRUE.equals(batch.calendarComplete());
		if (calendarKnown) {
			text(batch.calendarVersion(), "计划生产日历版本");
		} else {
			gaps.add("计划生产日历未确认完整，不能以自然日、派工计划或默认班次补齐");
		}
		BigDecimal plannedSeconds = calendarKnown ? sum(planned) : null;
		BigDecimal covered = intersection(planned, observations, null);
		BigDecimal run = intersection(planned, observations, "RUN");
		BigDecimal stop = intersection(planned, observations, "STOP");
		boolean runningKnown = calendarKnown && covered.compareTo(plannedSeconds) == 0
				&& run.add(stop).compareTo(plannedSeconds) == 0;
		if (!runningKnown) {
			gaps.add("设备RUN/STOP观察未完整覆盖已确认计划生产窗，缺口及UNKNOWN不能当作停机或运行");
		}
		BigDecimal cycle = decimal(batch.idealCycleSeconds(), true);
		if (cycle != null) {
			require(cycle.signum() > 0, "理想节拍必须大于0秒/原单位");
			text(batch.idealCycleVersion(), "理想节拍版本");
		} else {
			gaps.add("缺少已确认理想节拍秒/原单位；工位标准人时、APS取整占用不能替代");
		}
		boolean outputKnown = Boolean.TRUE.equals(batch.outputComplete());
		boolean goodKnown = outputKnown;
		if (!outputKnown) {
			gaps.add("周期产出未确认完整，已知子集不作为总产量或良品率总体");
		}
		BigDecimal total = BigDecimal.ZERO;
		BigDecimal good = BigDecimal.ZERO;
		ids.clear();
		for (Output row : batch.outputs()) {
			require(row != null, "产出来源不能为空");
			unique(ids, row.sourceId());
			text(row.taskCode(), "产出原工单");
			text(row.produceTaskCode(), "产出原工序任务");
			text(row.arrangeCode(), "产出原派工");
			require(Objects.equals(batch.deviceCode(), row.deviceCode())
					&& Objects.equals(batch.productCode(), row.productCode())
					&& Objects.equals(batch.routeNodeCode(), row.routeNodeCode())
					&& Objects.equals(batch.unit(), row.unit()), "产出设备、产品、稳定节点或原单位不一致，禁止混合计算");
			Instant at = time(row.eventTime());
			require(!at.isBefore(start) && at.isBefore(end), "产出事件不在统计周期内");
			boolean inPlan = planned.stream().anyMatch(s -> contains(s, at));
			Span observation = observations.stream().filter(s -> contains(s, at)).findFirst().orElse(null);
			require(!calendarKnown || inPlan, "产出事件不在已确认计划生产窗内");
			require(observation == null || !"STOP".equals(observation.state), "产出事件与设备STOP观察矛盾");
			if (!inPlan || observation == null || !"RUN".equals(observation.state)) {
				outputKnown = false;
				goodKnown = false;
				gaps.add("产出缺少同设备计划窗及RUN观察归属，不用任务号推断物理运行");
			}
			BigDecimal quantity = decimal(row.totalQuantity(), true);
			BigDecimal qualified = decimal(row.goodQuantity(), true);
			require(quantity == null || qualified == null || qualified.compareTo(quantity) <= 0,
					"良品数量不能超过总产出");
			if (quantity == null) {
				outputKnown = false;
				gaps.add("存在总产量未知的来源，不能当作0");
			} else {
				total = total.add(quantity);
			}
			if (qualified == null) {
				goodKnown = false;
				gaps.add("存在良品量待判或缺失，不能当作全合格或0");
			} else {
				good = good.add(qualified);
			}
		}
		total = outputKnown ? total : null;
		good = outputKnown && goodKnown ? good : null;
		BigDecimal idealTotal = total != null && cycle != null ? total.multiply(cycle) : null;
		if (runningKnown && idealTotal != null) {
			require(idealTotal.compareTo(run) <= 0, "理想产出时间超过设备RUN时间（性能率超过100%），请核源而非截平");
		}
		BigDecimal idealGood = good != null && cycle != null ? good.multiply(cycle) : null;
		Rate availability = rate(runningKnown ? run : null, plannedSeconds);
		Rate performance = rate(runningKnown ? idealTotal : null, runningKnown ? run : null);
		Rate quality = rate(good, total);
		Rate oee = rate(runningKnown ? idealGood : null, plannedSeconds);
		String status = "KNOWN".equals(oee.status()) ? "READY"
				: gaps.isEmpty() ? "NOT_APPLICABLE" : "PARTIAL";
		return new Measurement("MES_EQUIPMENT_OEE_V1", batch, status,
				new Metrics(value(plannedSeconds), runningKnown ? value(run) : null,
						runningKnown ? value(stop) : null, value(total), value(good), value(idealTotal),
						availability, performance, quality, oee), List.copyOf(gaps));
	}

	private static Rate rate(BigDecimal numerator, BigDecimal denominator) {
		String state = numerator == null || denominator == null ? "UNKNOWN"
				: denominator.signum() == 0 ? "NOT_APPLICABLE" : "KNOWN";
		return new Rate(state, value(numerator), value(denominator), "KNOWN".equals(state)
				? value(numerator.multiply(HUNDRED).divide(denominator, 6, RoundingMode.HALF_UP)) : null);
	}

	private static BigDecimal decimal(String text, boolean nullable) {
		if (text == null && nullable) {
			return null;
		}
		require(text != null && text.matches("(?:0|[1-9][0-9]{0,23})(?:\\.[0-9]{1,8})?"),
				"数量和节拍须用非负十进制文本，最多24位整数及8位小数");
		return new BigDecimal(text);
	}

	private static void text(String value, String label) {
		require(value != null && !value.isBlank() && value.equals(value.trim()) && value.length() <= 200
				&& value.chars().noneMatch(Character::isISOControl), label + "缺失或格式无效");
	}

	private static Instant time(String raw) {
		try {
			require(raw != null && raw.endsWith("Z"), "来源时间必须明确使用UTC Z格式");
			Instant value = Instant.parse(raw);
			require(value.getNano() % 1000 == 0, "来源时间最多保留微秒");
			return value;
		} catch (CheckedException failure) {
			throw failure;
		} catch (Exception failure) {
			throw new CheckedException("来源时间格式无效", failure);
		}
	}

	private static Span span(String from, String to, Instant start, Instant end, String state) {
		Instant a = time(from);
		Instant b = time(to);
		require(b.isAfter(a) && !a.isBefore(start) && !b.isAfter(end), "来源区间无效或超出统计周期");
		return new Span(a, b, state);
	}

	private static void ordered(List<Span> values) {
		values.sort(Comparator.comparing(Span::start));
		for (int i = 1; i < values.size(); i++) {
			require(!values.get(i).start.isBefore(values.get(i - 1).end), "同类来源区间重叠，不能重复累计或消解矛盾");
		}
	}

	private static BigDecimal sum(List<Span> values) {
		return values.stream().map(s -> seconds(s.start, s.end)).reduce(BigDecimal.ZERO, BigDecimal::add);
	}

	private static BigDecimal intersection(List<Span> planned, List<Span> observed, String kind) {
		BigDecimal sum = BigDecimal.ZERO;
		int j = 0;
		for (Span p : planned) {
			while (j < observed.size() && !observed.get(j).end.isAfter(p.start)) {
				j++;
			}
			for (int k = j; k < observed.size() && observed.get(k).start.isBefore(p.end); k++) {
				Span o = observed.get(k);
				Instant from = p.start.isAfter(o.start) ? p.start : o.start;
				Instant to = p.end.isBefore(o.end) ? p.end : o.end;
				if (to.isAfter(from) && (kind == null || kind.equals(o.state))) {
					sum = sum.add(seconds(from, to));
				}
			}
		}
		return sum;
	}

	private static boolean contains(Span span, Instant at) {
		return !at.isBefore(span.start) && at.isBefore(span.end);
	}

	private static BigDecimal seconds(Instant from, Instant to) {
		Duration d = Duration.between(from, to);
		return BigDecimal.valueOf(d.getSeconds()).add(BigDecimal.valueOf(d.getNano(), 9));
	}

	private static void unique(Set<String> seen, String id) {
		text(id, "原来源编号");
		require(seen.add(id), "同类来源编号重复");
	}

	private static String value(BigDecimal number) {
		return number == null ? null : number.stripTrailingZeros().toPlainString();
	}

	private static void require(boolean condition, String reason) {
		if (!condition) {
			throw new CheckedException(reason);
		}
	}

	private record Span(Instant start, Instant end, String state) {
	}
}
