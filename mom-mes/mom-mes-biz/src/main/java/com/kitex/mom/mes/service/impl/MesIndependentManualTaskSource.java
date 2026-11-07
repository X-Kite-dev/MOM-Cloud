package com.kitex.mom.mes.service.impl;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.entity.MesManualTaskRequestEntity;
import com.kitex.mom.mes.entity.MesProduceTaskEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.mapper.MesManualTaskMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.BeanWrapperImpl;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 已下达独立MOM手工工单的只读出生资格核验，不依赖工时变更批准层或今日MDM策略。
 * 调用方须先核实际工厂写权并持有同一工单根锁；本组件不授予派工或人员权限。
 */
@Component
@RequiredArgsConstructor
public class MesIndependentManualTaskSource {

	private static final ObjectMapper JSON = new ObjectMapper()
			.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
			.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
	private static final DateTimeFormatter COMMAND_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
			.withResolverStyle(ResolverStyle.STRICT);
	private static final Set<String> COMMAND_FIELDS = Set.of("requestId", "expectedTenantId", "expectedFactoryCode",
			"expectedActorId", "matCode", "engineeringReleaseId", "engineeringContentHash", "quantity", "type",
			"planStartDate", "planEndDate");
	private static final Set<String> UPSTREAM_FIELDS = Set.of("planNo", "erpProductionOrderNo", "resourceId",
			"materialReservationSnapshot", "toolingAllocationSnapshot", "toolingLifeUsageSnapshot", "reworkRequestId",
			"reworkMaterialPlanId", "sourceTaskCode", "sourceProduceTaskCode", "sourceInspectionType",
			"sourceInspectionTaskId", "sourceHandleRecordId", "reworkProcessCode", "reworkRouteNodeCode",
			"reworkTimingPolicySnapshot", "reworkEngineeringMode", "reworkEngineeringOverrideId",
			"reworkEngineeringOverrideHash", "reworkEngineeringScopeSnapshot", "reworkEngineeringScopeFingerprint",
			"reworkIdentifierScopeSnapshot", "reworkIdentifierScopeFingerprint");

	private final MesManualTaskMapper manual;
	private final MesIssuedEngineeringSource issuedSource;

	/**
	 * 任一手工标记出现即要求完整手工资格；标记不完整不能借PLAN分支放行。
	 * 此判断只用于选择校验路径，不表示来源已经有效。
	 */
	public boolean isManualCandidate(MesTaskEntity task) {
		return task != null && ("MANUAL".equals(task.getResourceType())
				|| task.getSourceRequestId() != null
				&& task.getSourceRequestId().startsWith(MesManualTaskService.PREFIX));
	}

	/**
	 * 在调用方真实根锁和READ_COMMITTED事务内核全部手工候选的出生回执、原工程及实际工序。
	 * 建单人可以不同于本次有权派工人；出生actor只与原command核对，不改成当前用户。
	 * PLAN资格仍由调用方核验；本批ready只读取一次，不跨请求缓存任何数据库或来源证明。
	 *
	 * @param tasks 本次实际组织内已下达工单，任一手工标记都必须通过严格手工来源校验
	 * @param processes 本次从原工单集合读取的完整冻结工序，不能混入其他组织或PLAN资源投影
	 * @throws CheckedException 出生原件、组织、工程或独立来源不能精确核实
	 */
	@Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
	public void validateAll(List<MesTaskEntity> tasks, List<MesProduceTaskEntity> processes) {
		require(tasks != null && processes != null && tasks.stream().allMatch(Objects::nonNull)
				&& processes.stream().allMatch(Objects::nonNull), "过程组合缺少完整原工单或工序来源");
		List<MesTaskEntity> manualTasks = tasks.stream().filter(this::isManualCandidate).toList();
		if (manualTasks.isEmpty()) {
			return;
		}
		installed();
		for (MesTaskEntity task : manualTasks) {
			List<MesProduceTaskEntity> members = processes.stream()
					.filter(process -> Objects.equals(process.getTaskCode(), task.getCode())).toList();
			validate(task, members);
		}
	}

	/** 同批ready核验后逐单核来源；父行与子工序均为根锁内读取的原对象。 */
	private void validate(MesTaskEntity task, List<MesProduceTaskEntity> processes) {
		require(task != null && task.getId() != null && task.getId() > 0 && text(task.getCode())
				&& task.getTenantId() != null && task.getTenantId() > 0
				&& Objects.equals(task.getTenantId(), TenantContextHolder.getTenantId())
				&& text(task.getFactoryCode())
				&& Objects.equals(task.getFactoryCode(), FactoryContextHolder.getFactoryCode())
				&& "0".equals(task.getDelFlag()), "当前组织无法核实原手工工单身份");
		require(MesManualTaskService.owns(task) && "MOM".equals(task.getSourceSystem())
				&& "MOM_MRP".equals(task.getMrpAuthority())
				&& "MOM_ORDER_ONLY".equals(task.getProductionOrderSyncMode())
				&& Objects.equals(task.getCode(), task.getProductionOrder())
				&& task.getPlanningPolicyId() != null && task.getPlanningPolicyId() > 0
				&& task.getPlanningPolicyVersion() != null && task.getPlanningPolicyVersion() > 0
				&& task.getPlanningPolicyFingerprint() != null
				&& task.getPlanningPolicyFingerprint().matches("[0-9a-f]{64}"),
				"手工工单缺少原独立MOM策略或出生来源");
		assertNoUpstream(task);
		require(manual.apsLinks(task.getTenantId(), task.getFactoryCode(), task.getId()) == 0,
				"手工工单已有APS分配历史，不能通过过程组合绕过原计划合同");
		require(manual.arrangementHistory(task.getTenantId(), task.getFactoryCode(), task.getCode()) == 0,
				"手工工单已有派工历史，请查看既有执行，不能再次创建首次过程组合");
		String requestId = task.getSourceRequestId().substring(MesManualTaskService.PREFIX.length());
		require(requestId.matches("[A-Za-z0-9_-]{16,64}"), "原手工建单请求号无效");
		MesManualTaskRequestEntity receipt = manual.receipt(task.getTenantId(), task.getFactoryCode(), requestId);
		validateReceipt(task, requestId, receipt);
		validateOriginalCommand(task, requestId, receipt);
		// 仅按保存原包重建合法下达投影；不重选今日工程或策略，也不写SOP签收事实。
		issuedSource.validate(task);
		require(processes != null && !processes.isEmpty() && processes.size() <= 200,
				"手工工单缺少完整冻结工序或节点数量超限");
		for (MesProduceTaskEntity process : processes) {
			require(process != null && process.getId() != null && process.getId() > 0
					&& Objects.equals(process.getTenantId(), task.getTenantId())
					&& Objects.equals(process.getFactoryCode(), task.getFactoryCode())
					&& Objects.equals(process.getTaskCode(), task.getCode()) && "0".equals(process.getDelFlag()),
					"手工工序归属缺失或与原工单不同");
			assertNoUpstream(process);
		}
	}

	/** 368精确目录及原件保护是手工来源前置，不把工时变更表作为额外依赖。 */
	private void installed() {
		Integer ready;
		try {
			ready = manual.ready();
		} catch (RuntimeException failure) {
			throw new CheckedException("手工过程组合需要先核验数据库升级368", failure);
		}
		require(Integer.valueOf(1).equals(ready), "手工建单回执或来源守卫未就绪，请核验数据库升级368");
	}

	/** 接受原件不随主单状态或当前派工人变化；全文SHA及原始归属必须保持一致。 */
	private void validateReceipt(MesTaskEntity task, String requestId, MesManualTaskRequestEntity receipt) {
		require(receipt != null && "ACCEPTED".equals(receipt.getState())
				&& Objects.equals(receipt.getTenantId(), task.getTenantId())
				&& Objects.equals(receipt.getFactoryCode(), task.getFactoryCode())
				&& Objects.equals(receipt.getRequestId(), requestId)
				&& Objects.equals(receipt.getTaskId(), task.getId())
				&& Objects.equals(receipt.getTaskCode(), task.getCode())
				&& receipt.getActorId() != null && receipt.getActorId() > 0
				&& receipt.getCommandPayload() != null
				&& Objects.equals(sha(receipt.getCommandPayload()), receipt.getCommandFingerprint())
				&& Objects.equals(receipt.getCommandFingerprint(), task.getSourceRequestFingerprint()),
				"原手工建单接受回执缺失或与工单身份、指纹不同");
	}

	/**
	 * 同包共用368出生原文解析，核原command与传入工单、原actor相符，不要求当前人员等于建单人。
	 * 该纯校验不查库或授予业务资格；调用方仍须核完整回执SHA、来源、权限及原工程。
	 */
	static void validateOriginalCommand(MesTaskEntity task, String requestId, MesManualTaskRequestEntity receipt) {
		require(task != null && task.getTenantId() != null && receipt != null && receipt.getActorId() != null
				&& receipt.getActorId() > 0 && receipt.getCommandPayload() != null && requestId != null,
				"原手工建单正文缺少可核身份");
		validateCommand(task, requestId, receipt, readCommand(receipt.getCommandPayload()));
	}

	/** 当前368原件是11个明确文本字段；不把旧数字/数组或未知字段猜成新出生合同。 */
	private static JsonNode readCommand(String raw) {
		JsonNode command;
		try {
			command = JSON.readTree(raw);
		} catch (JsonProcessingException failure) {
			throw new CheckedException("原手工建单正文损坏或含重复、尾随内容", failure);
		}
		require(command != null && command.isObject() && command.size() == COMMAND_FIELDS.size(),
				"原手工建单正文字段不完整或版本不受支持");
		for (String field : COMMAND_FIELDS) {
			require(command.path(field).isTextual(), "原手工建单字段须为明确文本：" + field);
		}
		return command;
	}

	/** 出生数量、日期与工程均不可通过合法草稿编辑修改，包装/批次配置不在本比较中。 */
	private static void validateCommand(MesTaskEntity task, String requestId, MesManualTaskRequestEntity receipt,
			JsonNode command) {
		require(Objects.equals(command.path("requestId").textValue(), requestId)
				&& Objects.equals(command.path("expectedTenantId").textValue(), task.getTenantId().toString())
				&& Objects.equals(command.path("expectedFactoryCode").textValue(), task.getFactoryCode())
				&& Objects.equals(command.path("expectedActorId").textValue(), receipt.getActorId().toString())
				&& Objects.equals(command.path("matCode").textValue(), task.getMatCode())
				&& Set.of("PART", "PRODUCT").contains(command.path("type").textValue())
				&& Objects.equals(command.path("type").textValue(), task.getType())
				&& task.getEngineeringReleaseId() != null && task.getEngineeringReleaseId() > 0
				&& Objects.equals(command.path("engineeringReleaseId").textValue(), task.getEngineeringReleaseId().toString())
				&& Objects.equals(command.path("engineeringContentHash").textValue(), task.getEngineeringContentHash()),
				"原建单正文与当前手工工单组织、产品或工程不同");
		String quantity = command.path("quantity").textValue();
		require(quantity.matches("[0-9]{1,12}(\\.[0-9]{1,8})?") && new BigDecimal(quantity).signum() > 0
				&& task.getNoticeNum() != null && task.getNoticeNum().compareTo(new BigDecimal(quantity)) == 0,
				"原手工建单数量与工单不同或计量格式无效");
		LocalDateTime start = commandTime(command.path("planStartDate").textValue());
		LocalDateTime end = commandTime(command.path("planEndDate").textValue());
		require(!end.isBefore(start) && Objects.equals(start, task.getPlanStartDate())
				&& Objects.equals(end, task.getPlanEndDate()), "原手工建单计划日期与工单不同");
	}

	/** 严格按原command秒精度日期解析，不能通过日期归一化接受损坏的出生原文。 */
	private static LocalDateTime commandTime(String raw) {
		try {
			LocalDateTime value = LocalDateTime.parse(raw, COMMAND_TIME);
			require(Objects.equals(COMMAND_TIME.format(value), raw), "原手工建单计划日期格式无效");
			return value;
		} catch (DateTimeParseException failure) {
			throw new CheckedException("原手工建单计划日期格式无效", failure);
		}
	}

	/** 同类计划资源按属性组核空，普通数量和合法包规/批次字段不当作外部来源。 */
	private void assertNoUpstream(Object bean) {
		BeanWrapperImpl view = new BeanWrapperImpl(bean);
		for (var descriptor : view.getPropertyDescriptors()) {
			String name = descriptor.getName();
			if (name.startsWith("aps") || name.startsWith("formalPlan") || name.startsWith("sourcePlan")
					|| name.startsWith("planned") && !"plannedNum".equals(name) || UPSTREAM_FIELDS.contains(name)) {
				require(view.getPropertyValue(name) == null, "独立手工来源已有计划、资源或返工投影：" + name);
			}
		}
	}

	private String sha(String raw) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException failure) {
			throw new IllegalStateException("原手工建单指纹算法不可用", failure);
		}
	}

	private boolean text(String value) {
		return value != null && !value.isBlank();
	}

	private static void require(boolean condition, String message) {
		if (!condition) {
			throw new CheckedException(message);
		}
	}
}
