package com.kitex.mom.plan.service.impl;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.dto.MdmFactoryAccessCheck;
import com.kitex.mom.mdm.feign.RemoteMdmFactoryAccessService;
import com.kitex.mom.mes.api.feign.RemoteMesTaskService;
import com.kitex.mom.mes.dto.MesPlanSourceMappingDTO;
import com.kitex.mom.plan.dto.PlnEngineeringAllocationDTO.Fact;
import com.kitex.mom.plan.dto.PlnEngineeringAllocationDTO.Observation;
import com.kitex.mom.plan.mapper.PlnEngineeringAllocationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * PLAN自有资源分配的完整有界观察。MES原映射前后双读，PLAN原件在同一可重复读快照读取；
 * 双读一致也不等于跨域原子快照，不产生准备锁、无占用证明或换版执行资格。
 */
@Service
@RequiredArgsConstructor
public class PlnEngineeringAllocationObservationService {

	private static final ObjectMapper JSON = new ObjectMapper()
			.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
			.setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));
	private static final int MAX_BYTES = 8 * 1024 * 1024;
	private final RemoteMesTaskService mes;
	private final RemoteMdmFactoryAccessService factories;
	private final PlnEngineeringAllocationMapper mapper;

	/**
	 * 仅接受原工单号，逐项匹配MES分配与PLAN原主键/批次/数量，并保留其他相关来源及缺口。
	 * 旧planNo、返工转引、不完整映射、超限或上游未知结果明确拒绝，不返回虚假的空占用。
	 */
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public Observation observe(String taskCode) {
		return capture(taskCode);
	}

	/**
	 * 准备工作流已持有全量来源事务锁，再按RC锁内重读；不另开连接，避免准备并发占满连接池。
	 * 调用范围限本包，真正来源锁及提交时原件校验仍由数据库守卫再次证明。
	 */
	Observation observeForPreparation(String taskCode) {
		require(TransactionSynchronizationManager.isActualTransactionActive()
				&& !TransactionSynchronizationManager.isCurrentTransactionReadOnly()
				&& Objects.equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),
				java.sql.Connection.TRANSACTION_READ_COMMITTED), "准备依据必须在已锁来源的RC写事务中重读");
		return capture(taskCode);
	}

	/**
	 * 共用完整观察算法；独立读取和已锁来源重读分别由各自事务合同约束。
	 */
	private Observation capture(String taskCode) {
		Scope scope = scope();
		require(taskCode != null && !taskCode.isBlank() && taskCode.equals(taskCode.trim())
				&& taskCode.length() <= 100, "请选择明确的原工单号");
		MesPlanSourceMappingDTO original = mapping(taskCode, scope);
		List<MesPlanSourceMappingDTO.Allocation> sources = original.getAllocations();
		require(sources != null && !sources.isEmpty() && sources.size() <= 500,
				"MES完整来源分配缺失或超过500条，不能截断取证");
		Set<String> unique = new HashSet<>();
		for (var source : sources) {
			require(source != null && positive(source.getIssueId()) && positive(source.getApsTaskId())
							&& positive(source.getApsBatchId()) && source.getAllocationSeq() != null
							&& source.getAllocationSeq() > 0 && source.getIssueNo() != null
							&& source.getApsBatchNo() != null && source.getAllocatedQuantity() != null
							&& source.getAllocatedQuantity().signum() > 0 && source.getIssueQuantitySnapshot() != null
							&& source.getIssueQuantitySnapshot().signum() > 0
							&& unique.add(source.getApsBatchId() + ":" + source.getIssueId()),
					"MES原来源主键、批次、数量或唯一关系不完整");
		}
		List<Long> issueIds = sources.stream().map(MesPlanSourceMappingDTO.Allocation::getIssueId).distinct().sorted()
				.toList();
		List<JsonNode> initial = read(mapper.allocations(scope.tenant(), scope.factory(), issueIds), 5000, scope);
		Set<Long> batchIds = new TreeSet<>();
		sources.forEach(row -> batchIds.add(row.getApsBatchId()));
		initial.forEach(row -> addId(batchIds, row, "batch_id"));
		require(batchIds.size() <= 5000, "PLAN关联批次超过5000条，不能截断取证");
		List<JsonNode> allocations = read(mapper.allocationScope(scope.tenant(), scope.factory(), issueIds,
				List.copyOf(batchIds)), 5000, scope);
		Set<Long> allIssues = new TreeSet<>(issueIds);
		allocations.forEach(row -> addId(allIssues, row, "issue_id"));
		require(allIssues.size() <= 5000, "关联来源计划超过5000条，不能截断取证");
		List<JsonNode> issues = read(mapper.issues(scope.tenant(), scope.factory(), List.copyOf(allIssues)), 5000,
				scope);
		List<JsonNode> batches = read(mapper.batches(scope.tenant(), scope.factory(), List.copyOf(batchIds)), 5000,
				scope);
		Set<Long> taskIds = new TreeSet<>();
		sources.forEach(row -> taskIds.add(row.getApsTaskId()));
		allocations.forEach(row -> addId(taskIds, row, "task_id"));
		batches.forEach(row -> addId(taskIds, row, "task_id"));
		require(taskIds.size() <= 5000, "关联排程运行超过5000条，不能截断取证");
		List<JsonNode> tasks = read(mapper.tasks(scope.tenant(), scope.factory(), List.copyOf(taskIds)), 5000, scope);
		List<JsonNode> operations = read(mapper.operations(scope.tenant(), scope.factory(), issueIds,
				List.copyOf(batchIds)), 10000, scope);
		List<Fact> facts = new ArrayList<>();
		append(facts, "ISSUE", issues);
		append(facts, "BATCH", batches);
		append(facts, "ALLOCATION", allocations);
		append(facts, "TASK", tasks);
		append(facts, "OPERATION", operations);
		require(facts.stream().mapToLong(row -> row.snapshotJson().getBytes(StandardCharsets.UTF_8).length).sum() <=
						MAX_BYTES,
				"PLAN完整分配原件超过8MiB，请缩小业务拆分范围，不能截断取证");
		List<String> gaps = gaps(original, issues, batches, tasks, allocations, operations);
		MesPlanSourceMappingDTO after = mapping(taskCode, scope);
		require(Objects.equals(original.getMesTaskId(), after.getMesTaskId())
						&& Objects.equals(original.getMappingFingerprint(), after.getMappingFingerprint())
						&& Objects.equals(original.getEngineeringReleaseId(), after.getEngineeringReleaseId())
						&& Objects.equals(original.getEngineeringContentHash(), after.getEngineeringContentHash())
						&& Objects.equals(original.getProductCode(), after.getProductCode()),
				"读取期间MES来源映射已变化，请重新核对");
		return new Observation("PLAN_ENGINEERING_ALLOCATION_OBSERVATION_V1", scope.tenant().toString(),
				scope.factory(), original.getMesTaskId().toString(), taskCode, original.getProductCode(),
				original.getEngineeringReleaseId(), original.getEngineeringContentHash(), Instant.now(),
				original.getMappingFingerprint(), fingerprint(scope, original, facts), gaps.isEmpty(),
				List.copyOf(facts), List.copyOf(gaps), false, null);
	}

	/**
	 * 实名、实际租户与工厂读授权均需成立，内部调用标记不能代替原人员权限。
	 */
	private Scope scope() {
		var user = SecurityUtils.getUser();
		var authentication = SecurityUtils.getAuthentication();
		Long tenant = TenantContextHolder.getTenantId();
		String factory = FactoryContextHolder.getFactoryCode();
		require(authentication != null && authentication.isAuthenticated() && user != null
				&& positive(user.getId()) && positive(tenant) && Objects.equals(user.getTenantId(), tenant)
				&& !TenantContextHolder.getTenantSkip() && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())
				&& factory != null && !factory.isBlank(), "请以当前租户实名登录并选择实际工厂");
		var response = factories.check(new MdmFactoryAccessCheck().setUserId(user.getId())
				.setFactoryCode(factory).setRequiredPermission("read"));
		require(response != null && response.isOk() && Boolean.TRUE.equals(response.getData()),
				"当前用户没有所需工厂读权限，或授权暂不可核验");
		return new Scope(tenant, factory);
	}

	/**
	 * 完整APS映射才有资格观察分配；旧计划号、返工来源转引和无来源均不是零占用。
	 */
	private MesPlanSourceMappingDTO mapping(String task, Scope scope) {
		var response = mes.planSourceMapping(task, null, scope.factory());
		var value = response == null ? null : response.getData();
		require(response != null && response.isOk() && value != null && positive(value.getMesTaskId())
						&& task.equals(value.getRequestedTaskCode()) && task.equals(value.getMesTaskCode())
						&& scope.factory().equals(value.getFactoryCode()) && Boolean.TRUE.equals(value.getMappingComplete())
						&& "APS_ALLOCATION".equals(value.getMappingStatus()) && value.getMappingFingerprint() != null
						&& value.getMappingFingerprint().matches("[0-9a-f]{64}")
						&& value.getEngineeringReleaseId() != null &&
						value.getEngineeringReleaseId().matches("[1-9][0-9]{0,18}")
						&& value.getEngineeringContentHash() != null &&
						value.getEngineeringContentHash().matches("[0-9a-f]{64}")
						&& value.getProductCode() != null && !value.getProductCode().isBlank(),
				"MES未返回该原工单的完整APS分配；历史计划号、返工转引或未知结果不能证明无占用");
		return value;
	}

	/**
	 * 数据库按单类8MiB封顶，Java再核跨类总量；原件ID及组织逐行复核，不信任首行归属。
	 */
	private List<JsonNode> read(List<String> rows, int limit, Scope scope) {
		require(rows != null && rows.size() <= limit, "PLAN原记录缺失或超过完整读取上限，禁止截断");
		List<JsonNode> result = new ArrayList<>();
		Set<String> ids = new HashSet<>();
		for (String raw : rows) {
			require(raw != null && raw.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES,
					"PLAN原件超过8MiB或未完整返回");
			try {
				JsonNode row = JSON.readTree(raw);
				require(row != null && row.isObject() && id(row, "id") != null
						&& ids.add(text(row, "id")) && scope.tenant().equals(id(row, "tenant_id"))
						&& scope.factory().equals(text(row, "factory_code")), "PLAN原件身份、组织或唯一性不一致");
				result.add(row);
			} catch (CheckedException failure) {
				throw failure;
			} catch (Exception failure) {
				throw new CheckedException("PLAN原件无法完整解析", failure);
			}
		}
		result.sort(Comparator.comparing(row -> id(row, "id")));
		return result;
	}

	/**
	 * 对原MES份额逐项匹配原PLAN分配；其他关联原件保留，不以同数量替代身份或工程版本。
	 */
	private List<String> gaps(MesPlanSourceMappingDTO mapping, List<JsonNode> issues,
	                          List<JsonNode> batches, List<JsonNode> tasks, List<JsonNode> allocations,
	                          List<JsonNode> operations) {
		List<String> gaps = new ArrayList<>();
		Map<Long, JsonNode> issueIndex = index(issues);
		Map<Long, JsonNode> batchIndex = index(batches);
		Map<Long, JsonNode> taskIndex = index(tasks);
		Map<AllocationKey, List<JsonNode>> allocationIndex = new HashMap<>();
		for (JsonNode row : allocations) {
			allocationIndex.computeIfAbsent(new AllocationKey(id(row, "batch_id"), id(row, "issue_id")),
					ignored -> new ArrayList<>()).add(row);
		}
		Set<Long> operationBatches = new HashSet<>();
		operations.forEach(row -> operationBatches.add(id(row, "scheduling_batch_id")));
		Map<Long, Integer> mesMembers = new HashMap<>();
		mapping.getAllocations().forEach(row -> mesMembers.merge(row.getApsBatchId(), 1, Integer::sum));
		Map<Long, Integer> planMembers = new HashMap<>();
		allocations.forEach(row -> planMembers.merge(id(row, "batch_id"), 1, Integer::sum));
		for (Long batchId : mesMembers.keySet()) {
			// 已逐项核MES→PLAN，成员数再反向核，防止同批多出PLAN来源却误称原映射完整。
			if (!Objects.equals(mesMembers.get(batchId), planMembers.get(batchId))) {
				gaps.add("原映射批次 " + batchId + " 的MES与PLAN完整来源成员集合不一致");
			}
		}
		splitCoverageGaps(mapping, issueIndex, allocations, gaps);
		for (var source : mapping.getAllocations()) {
			JsonNode issue = issueIndex.get(source.getIssueId());
			JsonNode batch = batchIndex.get(source.getApsBatchId());
			JsonNode task = taskIndex.get(source.getApsTaskId());
			var matched = allocationIndex.getOrDefault(new AllocationKey(source.getApsBatchId(), source.getIssueId()),
					List.of());
			if (issue == null || batch == null || task == null || matched.size() != 1) {
				gaps.add("原计划 " + source.getIssueNo() + " / 批次 " + source.getApsBatchNo() +
						" 的PLAN完整关系缺失或重复");
				continue;
			}
			JsonNode allocation = matched.get(0);
			if (!Objects.equals(mapping.getEngineeringReleaseId(), text(issue, "engineering_release_id"))
					|| !Objects.equals(mapping.getEngineeringContentHash(), text(issue, "engineering_content_hash"))
					|| !Objects.equals(mapping.getEngineeringReleaseId(), text(batch, "engineering_release_id"))
					|| !Objects.equals(mapping.getEngineeringContentHash(), text(batch, "engineering_content_hash"))
					|| !Objects.equals(mapping.getProductCode(), text(issue, "product_code"))
					|| !Objects.equals(mapping.getProductCode(), text(batch, "product_code"))) {
				gaps.add("原计划 " + source.getIssueNo() + " / 批次 " + source.getApsBatchNo() +
						" 的产品或工程版本与MES原工单不一致");
			}
			if (!Objects.equals(text(issue, "issue_no"), source.getIssueNo())
					|| !Objects.equals(text(batch, "batch_no"), source.getApsBatchNo())
					|| !Objects.equals(text(allocation, "issue_no"), source.getIssueNo())
					|| !Objects.equals(text(allocation, "batch_no"), source.getApsBatchNo())
					|| !Objects.equals(id(batch, "task_id"), source.getApsTaskId())
					|| !Objects.equals(id(allocation, "task_id"), source.getApsTaskId())
					|| !Objects.equals(text(allocation, "allocation_seq"), source.getAllocationSeq().toString())
					|| !sameNumber(allocation.get("allocated_quantity"), source.getAllocatedQuantity())
					|| !sameNumber(allocation.get("issue_quantity_snapshot"), source.getIssueQuantitySnapshot())) {
				gaps.add("原计划 " + source.getIssueNo() + " / 批次 " + source.getApsBatchNo() +
						" 与MES原分配身份或数量不一致");
			}
			if (!operationBatches.contains(source.getApsBatchId())) {
				gaps.add("批次 " + source.getApsBatchNo() + " 未找到原工序资源记录，不能推断无占用");
			}
		}
		for (var rows : List.of(issues, batches, tasks, allocations, operations)) {
			for (JsonNode row : rows) {
				if (!"0".equals(text(row, "del_flag"))) {
					gaps.add("原记录 " + text(row, "id") + " 已删除或有效标记未知，原件保留供核对");
				}
			}
		}
		relationshipGaps(issues, batches, tasks, allocations, operations, gaps);
		return gaps;
	}


	/**
	 * 同一运行/计划的所有有效拆批份额必须覆盖原数量，不能用单个自洽批次证明拆分完整。
	 */
	private void splitCoverageGaps(MesPlanSourceMappingDTO mapping, Map<Long, JsonNode> issues,
	                               List<JsonNode> allocations, List<String> gaps) {
		Map<RunIssueKey, List<JsonNode>> groups = new HashMap<>();
		for (JsonNode row : allocations) {
			if ("0".equals(text(row, "del_flag"))) {
				groups.computeIfAbsent(new RunIssueKey(id(row, "task_id"), id(row, "issue_id")),
						ignored -> new ArrayList<>()).add(row);
			}
		}
		Set<RunIssueKey> checked = new HashSet<>();
		for (var source : mapping.getAllocations()) {
			var key = new RunIssueKey(source.getApsTaskId(), source.getIssueId());
			if (!checked.add(key)) {
				continue;
			}
			List<JsonNode> members = groups.getOrDefault(key, List.of());
			JsonNode issue = issues.get(source.getIssueId());
			boolean complete = !members.isEmpty() && issue != null
					&& sameNumber(issue.get("num"), source.getIssueQuantitySnapshot())
					&& members.stream().allMatch(row -> row.path("allocated_quantity").isNumber()
					&& row.path("allocated_quantity").decimalValue().signum() > 0
					&& sameNumber(row.get("issue_quantity_snapshot"), source.getIssueQuantitySnapshot()));
			BigDecimal total = complete ? members.stream().map(row -> row.get("allocated_quantity").decimalValue())
					.reduce(BigDecimal.ZERO, BigDecimal::add) : null;
			if (total == null || total.compareTo(source.getIssueQuantitySnapshot()) != 0) {
				gaps.add("排程运行 " + source.getApsTaskId() + " / 原计划 " + source.getIssueNo()
						+ " 的有效拆批份额与原计划数量不守恒，删除历史另列保留");
			}
		}
	}

	/**
	 * 同批其他来源也须有完整归属，批次数量逐原份额求和；孤儿和旧缺节点不能静默丢掉。
	 */
	private void relationshipGaps(List<JsonNode> issues, List<JsonNode> batches, List<JsonNode> tasks,
	                              List<JsonNode> allocations, List<JsonNode> operations, List<String> gaps) {
		// 按原主键建索引，避免完整范围下对每条工序重新扫描全部分配，造成平方级读取卡顿。
		Map<Long, JsonNode> issueIndex = index(issues);
		Map<Long, JsonNode> batchIndex = index(batches);
		Map<Long, JsonNode> taskIndex = index(tasks);
		Map<Long, List<JsonNode>> membersByBatch = new HashMap<>();
		Set<AllocationKey> relations = new HashSet<>();
		for (JsonNode row : allocations) {
			membersByBatch.computeIfAbsent(id(row, "batch_id"), ignored -> new ArrayList<>()).add(row);
			relations.add(new AllocationKey(id(row, "batch_id"), id(row, "issue_id")));
		}
		for (JsonNode allocation : allocations) {
			JsonNode issue = issueIndex.get(id(allocation, "issue_id"));
			JsonNode batch = batchIndex.get(id(allocation, "batch_id"));
			if (issue == null || batch == null || taskIndex.get(id(allocation, "task_id")) == null
					|| !Objects.equals(text(allocation, "task_id"), text(batch, "task_id"))
					|| !Objects.equals(text(allocation, "issue_no"), text(issue, "issue_no"))
					|| !Objects.equals(text(allocation, "batch_no"), text(batch, "batch_no"))
					|| !Objects.equals(text(issue, "engineering_release_id"), text(batch, "engineering_release_id"))
					||
					!Objects.equals(text(issue, "engineering_content_hash"), text(batch, "engineering_content_hash"))) {
				gaps.add("分配原记录 " + text(allocation, "id") + " 的计划、批次、运行或工程关系不完整");
			}
		}
		for (JsonNode batch : batches) {
			var members = membersByBatch.getOrDefault(id(batch, "id"), List.of());
			boolean quantitiesKnown = members.stream().allMatch(row -> row.path("allocated_quantity").isNumber()
					&& row.path("allocated_quantity").decimalValue().signum() > 0);
			BigDecimal quantity = quantitiesKnown ? members.stream()
					.map(row -> row.get("allocated_quantity").decimalValue())
					.reduce(BigDecimal.ZERO, BigDecimal::add) : null;
			if (members.isEmpty() || !Objects.equals(text(batch, "allocation_count"), Integer.toString(members.size()))
					|| !sameNumber(batch.get("planned_quantity"), quantity)) {
				gaps.add("批次 " + text(batch, "batch_no") + " 的来源份额数量或合计不守恒");
			}
		}
		for (JsonNode operation : operations) {
			JsonNode batch = batchIndex.get(id(operation, "scheduling_batch_id"));
			if (batch == null || !Objects.equals(text(operation, "task_id"), text(batch, "task_id"))
					|| !Objects.equals(text(operation, "scheduling_batch_no"), text(batch, "batch_no"))
					|| id(batch, "primary_issue_id") == null
					|| !Objects.equals(id(operation, "issue_id"), id(batch, "primary_issue_id"))
					|| text(batch, "primary_issue_no") == null
					|| !Objects.equals(text(operation, "issue_no"), text(batch, "primary_issue_no"))
					|| text(batch, "product_code") == null
					|| !Objects.equals(text(operation, "product_code"), text(batch, "product_code"))
					|| !sameNumber(operation.get("planned_quantity"), batch.path("planned_quantity").isNumber()
					? batch.get("planned_quantity").decimalValue() : null)
					|| text(batch, "allocation_snapshot") == null || text(batch, "allocation_snapshot").isBlank()
					|| !Objects.equals(text(operation, "batch_allocation_snapshot"), text(batch, "allocation_snapshot"))
					|| text(operation, "source_route_node_code") == null ||
					text(operation, "source_route_node_code").isBlank()
					|| !relations.contains(
					new AllocationKey(id(operation, "scheduling_batch_id"), id(operation, "issue_id")))) {
				gaps.add("资源工序原记录 " + text(operation, "id") + " 的主计划、批量、产品、原分配快照或稳定节点不完整");
			}
		}
	}

	/**
	 * 文本投影只取实际保存字段；原件另保留，不从相同主工序猜稳定节点。
	 */
	private void append(List<Fact> facts, String kind, List<JsonNode> rows) {
		for (JsonNode row : rows) {
			facts.add(new Fact(kind, text(row, "id"), first(row, "issue_no", "batch_no", "task_no", "process_code"),
					text(row, "issue_id"), first(row, "scheduling_batch_id", "batch_id"),
					text(row, "source_route_node_code"),
					first(row, "schedule_status", "batch_status", "task_status", "status"), text(row, "del_flag"),
					text(row, "resource_type"), text(row, "resource_code"), text(row, "device_code"),
					text(row, "employee_code"),
					text(row, "tooling_codes"), text(row, "scheduled_start_time"), text(row, "scheduled_end_time"),
					first(row, "allocated_quantity", "planned_quantity", "num"), row.toString()));
		}
	}

	/**
	 * 原件及映射按有序JSON编码做摘要，避免业务编码中的分隔符造成碰撞。
	 */
	private String fingerprint(Scope scope, MesPlanSourceMappingDTO mapping, List<Fact> facts) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(
					List.of(scope.tenant().toString(), scope.factory(), mapping.getMesTaskId().toString(),
							mapping.getMesTaskCode(), mapping.getMappingFingerprint(), mapping.getProductCode(),
							mapping.getEngineeringReleaseId(), mapping.getEngineeringContentHash(), facts))));
		} catch (Exception failure) {
			throw new CheckedException("PLAN分配观察摘要生成失败", failure);
		}
	}

	private static Map<Long, JsonNode> index(List<JsonNode> rows) {
		Map<Long, JsonNode> values = new HashMap<>();
		rows.forEach(row -> values.put(id(row, "id"), row));
		return values;
	}

	private static void addId(Set<Long> values, JsonNode row, String field) {
		Long value = id(row, field);
		require(value != null, "PLAN原关系缺少合法主键：" + field);
		values.add(value);
	}

	private static Long id(JsonNode row, String field) {
		String value = text(row, field);
		if (value == null || !value.matches("[1-9][0-9]{0,18}")) {
			return null;
		}
		try {
			return Long.valueOf(value);
		} catch (NumberFormatException invalid) {
			return null;
		}
	}

	private static String text(JsonNode row, String field) {
		JsonNode value = row.get(field);
		return value == null || value.isNull() || !value.isValueNode() ? null : value.asText();
	}

	private static String first(JsonNode row, String... fields) {
		for (String field : fields) {
			String value = text(row, field);
			if (value != null) {
				return value;
			}
		}
		return null;
	}

	private static boolean sameNumber(JsonNode raw, BigDecimal expected) {
		return raw != null && raw.isNumber() && expected != null && raw.decimalValue().compareTo(expected) == 0;
	}

	private static boolean positive(Long value) {
		return value != null && value > 0;
	}

	private static void require(boolean condition, String message) {
		if (!condition) {
			throw new CheckedException(message);
		}
	}

	/**
	 * 当前认证组织，仅在已核验请求内使用。
	 */
	private record Scope(Long tenant, String factory) {
	}

	/**
	 * 数值主键对保留字段边界，不把编码或展示分隔串当作分配身份。
	 */
	private record AllocationKey(Long batchId, Long issueId) {
	}

	/**
	 * 同一排程运行与计划的拆批范围，不混加其他运行或已删除历史份额。
	 */
	private record RunIssueKey(Long taskId, Long issueId) {
	}

}
