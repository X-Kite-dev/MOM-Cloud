package com.kitex.mom.mes.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mes.dto.MesProductionPauseDTO;
import com.kitex.mom.mes.dto.MesProductionTimeDTO.Detail;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 暂停原因的完整来源分组和区间并集；不分摊设备、人时或重叠原因。
 */
final class MesProductionPauseCalculator {

	private static final ObjectMapper JSON = new ObjectMapper();
	private static final Set<String> KINDS = Set.of("PAUSE", "WORKPIECE_PAUSE");
	private static final DateTimeFormatter LOCAL_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSSSSS");

	/**
	 * 先从与原V1相同的完整来源裁剪，再按原原因键筛选、分页；未知键返回明确空明细。
	 * 调用方已验证组织、输入范围及20000来源上限，任何无效来源仍可下钻。
	 */
	MesProductionPauseDTO.View calculate(MesProductionTimeService.Snapshot snapshot, String selectedKey) {
		List<Detail> rows = new MesProductionTimeCalculator()
				.clipAndSort(snapshot.query(), snapshot.facts(), snapshot.observedAt()).stream()
				.filter(row -> KINDS.contains(row.getFact().getKind())).toList();
		Map<String, List<Detail>> groups = new LinkedHashMap<>();
		for (Detail row : rows) {
			groups.computeIfAbsent(key(row), ignored -> new ArrayList<>()).add(row);
		}
		List<MesProductionPauseDTO.Reason> reasons = new ArrayList<>();
		groups.forEach((key, group) -> {
			var fact = group.get(0).getFact();
			reasons.add(new MesProductionPauseDTO.Reason(key, fact.getKind(), fact.getReason(),
					!MesProductionTimeCalculator.unclassified(fact), metrics(group)));
		});
		List<Detail> matching = selectedKey == null ? rows : groups.getOrDefault(selectedKey, List.of());
		long from = ((long) snapshot.query().getDetailPage() - 1) * snapshot.query().getDetailSize();
		List<Detail> page = from >= matching.size() ? List.of()
				: List.copyOf(
				matching.subList((int) from, (int) Math.min(matching.size(), from + snapshot.query().getDetailSize())));
		Set<String> gaps = new LinkedHashSet<>();
		for (Detail row : rows) {
			if (row.getIssue() != null) {
				gaps.add(row.getIssue());
			}
		}
		if (snapshot.orphaned() > 0) {
			gaps.add("原统计范围有" + snapshot.orphaned() + "条时间来源缺少精确派工关联，暂停归属不可核定，未计入本表");
		}
		return new MesProductionPauseDTO.View("MES_PRODUCTION_PAUSE_V1", snapshot.factory(), scope(snapshot),
				selectedKey,
				time(snapshot.observedAt()),
				metrics(rows.stream().filter(row -> "PAUSE".equals(row.getFact().getKind())).toList()),
				metrics(rows.stream().filter(row -> "WORKPIECE_PAUSE".equals(row.getFact().getKind())).toList()),
				List.copyOf(reasons), page.stream().map(this::interval).toList(), matching.size(), List.copyOf(gaps));
	}

	/**
	 * 新合同显式保留原查询精确当地时间，不影响旧V1的全局序列化方式。
	 */
	private MesProductionPauseDTO.Scope scope(MesProductionTimeService.Snapshot snapshot) {
		var query = snapshot.query();
		return new MesProductionPauseDTO.Scope(time(query.getStartTime()), time(query.getEndTime()),
				query.getTaskCode(),
				query.getProduceTaskCode(), query.getArrangeCode(), query.getWorkshopCode(), query.getShiftCode(),
				query.getIdentifier(), query.getIdentifierType(), query.getGroupBy(), query.getDetailPage(),
				query.getDetailSize());
	}

	/**
	 * 原来源和裁剪时间均投影为完整精度文本，避免秒级JSON格式使时长无法按原件回算。
	 */
	private MesProductionPauseDTO.Interval interval(Detail row) {
		var fact = row.getFact();
		var source = new MesProductionPauseDTO.Source(fact.getSourceType(), fact.getSourceId(), fact.getTaskCode(),
				fact.getProduceTaskCode(), fact.getArrangeCode(), fact.getWorkshopCode(), fact.getShiftCode(),
				fact.getDeviceCode(), fact.getWorkerCode(), fact.getIdentifier(), fact.getIdentifierType(),
				fact.getKind(),
				time(fact.getStartTime()), time(fact.getEndTime()), fact.getState(), fact.getEligible(),
				fact.getReason());
		return new MesProductionPauseDTO.Interval(source, time(row.getWindowStart()), time(row.getWindowEnd()),
				row.getSeconds(), row.isIncluded(), row.isOngoing(), row.getIssue());
	}

	/**
	 * null保持缺失，非空时间保留全部纳秒，不做时区转换或毫秒化。
	 */
	private String time(LocalDateTime value) {
		return value == null ? null : value.format(LOCAL_TIME);
	}

	/**
	 * 原始原因及类型按无歧义JSON编码取指纹；键不含租户授权或写命令含义。
	 */
	private String key(Detail row) {
		try {
			String raw = JSON.writeValueAsString(Arrays.asList(row.getFact().getKind(), row.getFact().getReason()));
			return HexFormat.of()
					.formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
		} catch (JsonProcessingException | NoSuchAlgorithmException failure) {
			throw new CheckedException("暂停原因键无法生成", failure);
		}
	}

	/**
	 * 已闭合、进行中和全部各自求并集；无有效来源返回null，不机械相加三个口径。
	 */
	private MesProductionPauseDTO.Metrics metrics(List<Detail> rows) {
		List<Detail> included = rows.stream().filter(Detail::isIncluded).toList();
		List<Detail> ongoing = included.stream().filter(Detail::isOngoing).toList();
		return new MesProductionPauseDTO.Metrics(union(included),
				union(included.stream().filter(row -> !row.isOngoing()).toList()),
				union(ongoing), rows.size(), rows.size() - included.size(), ongoing.size());
	}

	/**
	 * 同实际工单/工序/派工、工件类型及原标识内去重，不合并不同工序的同名实物。
	 */
	private String union(List<Detail> rows) {
		if (rows.isEmpty()) {
			return null;
		}
		Map<List<String>, List<Detail>> resources = new LinkedHashMap<>();
		for (Detail row : rows) {
			var fact = row.getFact();
			List<String> resource = Arrays.asList(fact.getTaskCode(), fact.getProduceTaskCode(), fact.getArrangeCode(),
					fact.getKind(), fact.getIdentifierType(), fact.getIdentifier());
			resources.computeIfAbsent(resource, ignored -> new ArrayList<>()).add(row);
		}
		long total = 0;
		for (List<Detail> intervals : resources.values()) {
			intervals.sort(Comparator.comparing(Detail::getWindowStart));
			LocalDateTime start = intervals.get(0).getWindowStart();
			LocalDateTime end = intervals.get(0).getWindowEnd();
			for (int index = 1; index < intervals.size(); index++) {
				Detail next = intervals.get(index);
				if (next.getWindowStart().isAfter(end)) {
					total = Math.addExact(total, micros(start, end));
					start = next.getWindowStart();
					end = next.getWindowEnd();
				} else if (next.getWindowEnd().isAfter(end)) {
					end = next.getWindowEnd();
				}
			}
			total = Math.addExact(total, micros(start, end));
		}
		return BigDecimal.valueOf(total, 6).stripTrailingZeros().toPlainString();
	}

	/**
	 * 使用与原工时相同的微秒精度及溢出保护；异常不吞成零。
	 */
	private long micros(LocalDateTime start, LocalDateTime end) {
		Duration duration = Duration.between(start, end);
		return Math.addExact(Math.multiplyExact(duration.getSeconds(), 1_000_000), duration.getNano() / 1000);
	}
}
