package com.kitex.mom.mes.service.impl;

import cn.hutool.json.JSONUtil;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.dto.MesDispatchReturnDTO;
import com.kitex.mom.mes.dto.MesExecutionContractDTO;
import com.kitex.mom.mes.entity.MesDispatchAllocationEntity;
import com.kitex.mom.mes.entity.MesDispatchCommandEntity;
import com.kitex.mom.mes.entity.MesProduceTaskEntity;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.mapper.MesDispatchAllocationMapper;
import com.kitex.mom.mes.mapper.MesDispatchQuotaReturnMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 普通配额目标独立退派的来源核对、稳定证据和一次释放验证。
 *
 * <p>只处理400同事务出生、原生节点、非返工及普通NONE动作；不判断审批身份，
 * 不放宽原受控修正，也不读取今日模板。调用流程先取得原ROOT互斥、修订准入及实际工厂权限。</p>
 */
@Service
@RequiredArgsConstructor
public class MesDispatchQuotaReturnSupport {

	/** 新申请的目标级取证协议；旧申请必须按其原MES_DISPATCH_RETURN_V1合同办理。 */
	public static final String EVIDENCE_VERSION = "MES_DISPATCH_QUOTA_RETURN_V1";

	private final MesDispatchQuotaReturnMapper sourceMapper;
	private final MesDispatchAllocationMapper allocationMapper;
	private final MesDispatchAllocationLedger allocationLedger;
	private final MesPhysicalTransformPolicyValidator physicalPolicies;

	/**
	 * 在当前实际组织内核定是否适用目标级配额退派，并采集原件和严格零执行事实。
	 *
	 * @param arrange 真实派工；写流程已持原ROOT及该行锁
	 * @param task 同组织原生产工单，不冻结其当前状态、时间或累计实绩
	 * @param operation 同组织真实工序任务，不以可重复主工序码代替
	 * @param factory 调用流程核权取得的实际工厂，必须等于当前工厂上下文
	 * @return 精确配额上下文；完全未安装400、无配额或明确不适用的新范围返回null并沿历史合同
	 * @throws CheckedException 部分安装、来源不完整或身份/数量/原文不一致时拒绝，不能降级规避
	 */
	public QuotaContext inspect(MesTaskArrangeEntity arrange, MesTaskEntity task,
			MesProduceTaskEntity operation, String factory) {
		Long tenant = tenant(factory);
		require(arrange != null && task != null && operation != null, "退派缺少真实派工、工单或工序");
		require(Objects.equals(tenant, arrange.getTenantId()) && Objects.equals(tenant, task.getTenantId())
				&& Objects.equals(tenant, operation.getTenantId()) && Objects.equals(factory, arrange.getFactoryCode())
				&& Objects.equals(factory, task.getFactoryCode()) && Objects.equals(factory, operation.getFactoryCode()),
				"退派配额对象必须属于当前实际租户工厂");
		Integer state = sourceMapper.structureState();
		if (Objects.equals(state, 0)) {
			return null;
		}
		require(Objects.equals(state, 1), "派工配额结构部分安装，不能按旧无账退派办理");
		require(Objects.equals(allocationMapper.ready(), 1), "第400节配额来源及释放守卫未就绪");
		MesDispatchAllocationEntity allocation = allocationMapper.allocation(tenant, factory, arrange.getId());
		if (allocation == null) {
			return null;
		}
		assertSource(allocation, arrange, task, operation);
		if ("REWORK".equals(task.getResourceType())
				|| !"NODE_EXECUTION_V1".equals(arrange.getRouteExecutionSchemaVersion())
				|| !"NODE_EXECUTION_V1".equals(operation.getRouteExecutionSchemaVersion())) {
			return null;
		}
		MesExecutionContractDTO frozen = frozen(arrange);
		String action = frozen.getIdentifierNodePolicy().getAction();
		require(Set.of("NONE", "BIND", "USE", "RELEASE").contains(action), "原派工节点动作未知，不能判断独立退派范围");
		if (!"NONE".equals(action)
				|| !MesPhysicalTransformPolicyValidator.NONE.equals(physicalPolicies.readFrozen(arrange).getConfigurationJson())) {
			return null;
		}
		MesDispatchCommandEntity command = sourceMapper.command(tenant, factory, allocation.getCommandId());
		require(command != null && "ACCEPTED".equals(command.getStatus())
				&& "0".equals(command.getDelFlag()) && Objects.equals(command.getTaskCode(), task.getCode())
				&& command.getActorId() != null && command.getActorCode() != null
				&& command.getRequestId() != null && command.getRequestJson() != null
				&& digest(command.getRequestJson()).equals(command.getRequestFingerprint()),
				"原派工配额缺少完整不可变受理命令");
		Map<String, Object> identity = identity(allocation, command, arrange, task, operation);
		return new QuotaContext(allocation, Collections.unmodifiableMap(identity),
				Boolean.TRUE.equals(sourceMapper.noExecution(tenant, factory, arrange.getId())));
	}

	/**
	 * 关闭目标后核同事务配额已严格释放；失败回滚原关闭和审批，不推断WMS库存/预留变化。
	 *
	 * @param context 关闭前已核定的同一真实配额上下文
	 * @param closed 当前已关闭目标派工；必须仍为该配额身份且数据库没有任何执行证据
	 * @return 当前UNEXECUTED_CLOSED、占用0的精确配额摘要
	 */
	@Transactional(propagation = Propagation.MANDATORY, isolation = Isolation.READ_COMMITTED,
			rollbackFor = Exception.class)
	public MesDispatchReturnDTO.AllocationEvidence settleAndRead(QuotaContext context, MesTaskArrangeEntity closed) {
		require(context != null && closed != null && "CLOSE".equals(closed.getStatus()), "配额释放必须对应已关闭目标");
		Long tenant = tenant(closed.getFactoryCode());
		require(Objects.equals(context.allocation().getArrangeId(), closed.getId())
				&& Objects.equals(context.allocation().getTenantId(), tenant)
				&& Objects.equals(context.allocation().getFactoryCode(), closed.getFactoryCode())
				&& Boolean.TRUE.equals(sourceMapper.noExecution(tenant, closed.getFactoryCode(), closed.getId())),
				"原目标已有执行事实或释放身份变化，审批须整体回滚");
		allocationLedger.settleTerminal(closed);
		MesDispatchAllocationEntity current = allocationMapper.allocation(tenant, closed.getFactoryCode(), closed.getId());
		require(current != null && Objects.equals(current.getId(), context.allocation().getId())
				&& "UNEXECUTED_CLOSED".equals(current.getSettlementStatus())
				&& current.getOccupiedQuantity() != null && current.getOccupiedQuantity().signum() == 0,
				"目标配额未完成一次释放，原退派与审批回滚");
		return summary(current);
	}

	/** 返回本次实际配额的只读精确摘要；null表示沿历史合同，不冒充配额0。 */
	public MesDispatchReturnDTO.AllocationEvidence toEvidence(QuotaContext context) {
		return context == null ? null : summary(context.allocation());
	}

	/**
	 * 目标级取证的内部只读载体；identityFacts不包含父累计/在制状态，避免兄弟进度使申请失效。
	 *
	 * @param allocation 原400出生配额及当前结算状态
	 * @param identityFacts 原身份、数量、工程及资源摘要；不可变外层映射且业务数值按文本保留
	 * @param noExecution 原数据库严格零执行判定，false或未知不得批准独立释放
	 */
	public record QuotaContext(MesDispatchAllocationEntity allocation, Map<String, Object> identityFacts,
			boolean noExecution) {
	}

	/** 校对原配额、真实父子、稳定节点与精确工程；不猜全量或给历史补造配额。 */
	private void assertSource(MesDispatchAllocationEntity quota, MesTaskArrangeEntity arrange,
			MesTaskEntity task, MesProduceTaskEntity operation) {
		require("0".equals(quota.getDelFlag()) && quota.getId() != null && quota.getCommandId() != null
				&& Objects.equals(quota.getArrangeId(), arrange.getId()) && Objects.equals(quota.getArrangeCode(), arrange.getCode())
				&& Objects.equals(quota.getTaskId(), task.getId()) && Objects.equals(quota.getTaskCode(), task.getCode())
				&& Objects.equals(quota.getProduceTaskId(), operation.getId())
				&& Objects.equals(quota.getProduceTaskCode(), operation.getCode())
				&& Objects.equals(arrange.getTaskCode(), task.getCode()) && Objects.equals(operation.getTaskCode(), task.getCode())
				&& Objects.equals(arrange.getProduceTaskCode(), operation.getCode())
				&& quota.getSourceRouteNodeCode() != null
				&& Objects.equals(quota.getSourceRouteNodeCode(), operation.getSourceRouteNodeCode())
				&& Objects.equals(quota.getSourceRouteNodeCode(), arrange.getSourceRouteNodeCode())
				&& quota.getEngineeringReleaseId() != null && quota.getEngineeringContentHash() != null
				&& Objects.equals(quota.getEngineeringReleaseId(), task.getEngineeringReleaseId())
				&& Objects.equals(quota.getEngineeringContentHash(), task.getEngineeringContentHash())
				&& quota.getAllocatedQuantity() != null && quota.getAllocatedQuantity().signum() > 0
				&& arrange.getNoticeNum() != null && quota.getAllocatedQuantity().compareTo(arrange.getNoticeNum()) == 0,
				"原配额与真实派工、稳定节点或精确工程来源不一致");
	}

	/** 只解析原冻结策略并要求明确节点动作；不按今日模板补缺失普通动作。 */
	private MesExecutionContractDTO frozen(MesTaskArrangeEntity arrange) {
		require(arrange.getExecutionPolicySnapshot() != null && !arrange.getExecutionPolicySnapshot().isBlank(),
				"独立配额退派缺少原冻结执行策略");
		try {
			var json = JSONUtil.parseObj(arrange.getExecutionPolicySnapshot());
			require(json.getJSONObject("identifierNodePolicy") != null
					&& json.getJSONObject("identifierNodePolicy").getStr("action") != null,
					"原冻结节点动作缺失，不能猜测独立退派范围");
			return JSONUtil.toBean(json, MesExecutionContractDTO.class);
		} catch (RuntimeException failure) {
			throw new CheckedException("原冻结执行策略无法完整核对，请保留原件处理", failure);
		}
	}

	/** 冻结稳定依据，排除父status/time/complete/quality等随兄弟执行推进的字段。 */
	private Map<String, Object> identity(MesDispatchAllocationEntity quota, MesDispatchCommandEntity command,
			MesTaskArrangeEntity arrange, MesTaskEntity task, MesProduceTaskEntity operation) {
		Map<String, Object> facts = new LinkedHashMap<>();
		facts.put("allocationId", quota.getId().toString());
		facts.put("allocationKey", quota.getAllocationKey());
		facts.put("commandId", command.getId().toString());
		facts.put("commandRequestId", command.getRequestId());
		facts.put("commandFingerprint", command.getRequestFingerprint());
		facts.put("commandActorId", command.getActorId().toString());
		facts.put("commandActorCode", command.getActorCode());
		facts.put("allocatedQuantity", decimal(quota.getAllocatedQuantity()));
		facts.put("taskId", task.getId().toString());
		facts.put("taskCode", task.getCode());
		facts.put("taskQuantity", decimal(task.getNoticeNum()));
		facts.put("operationId", operation.getId().toString());
		facts.put("operationCode", operation.getCode());
		facts.put("operationQuantity", decimal(operation.getNoticeNum()));
		facts.put("processRouteCode", operation.getProcessRouteCode());
		facts.put("routeCode", operation.getRouteCode());
		facts.put("sourceRouteNodeCode", quota.getSourceRouteNodeCode());
		facts.put("engineeringReleaseId", quota.getEngineeringReleaseId().toString());
		facts.put("engineeringContentHash", quota.getEngineeringContentHash());
		facts.put("engineeringBundleHash", digest(task.getEngineeringBundleSnapshot()));
		facts.put("operationRouteHash", digest(operation.getProcessRouteSnapshot()));
		facts.put("executionPolicyHash", digest(arrange.getExecutionPolicySnapshot()));
		facts.put("shiftCode", arrange.getShiftCode());
		return facts;
	}

	/** API数量与Long保持文本，当前占用未知不补成零。 */
	private MesDispatchReturnDTO.AllocationEvidence summary(MesDispatchAllocationEntity quota) {
		return new MesDispatchReturnDTO.AllocationEvidence().setId(quota.getId())
				.setAllocationKey(quota.getAllocationKey()).setAllocatedQuantity(quota.getAllocatedQuantity())
				.setOccupiedQuantity(quota.getOccupiedQuantity()).setSettlementStatus(quota.getSettlementStatus());
	}

	/** 当前真实组织必须由外层已核权上下文取得，禁止skip模式借原对象探测其他工厂。 */
	private Long tenant(String factory) {
		Long tenant = TenantContextHolder.getTenantId();
		require(tenant != null && tenant > 0 && !TenantContextHolder.getTenantSkip()
				&& factory != null && !factory.isBlank() && Objects.equals(factory, FactoryContextHolder.getFactoryCode())
				&& !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()), "独立退派缺少实际租户工厂上下文");
		return tenant;
	}

	/** 可空原值保留未知，与真实0区分；非空值不经过浮点数。 */
	private String decimal(BigDecimal value) {
		return value == null ? null : value.stripTrailingZeros().toPlainString();
	}

	/** 原文SHA256；空原件以显式空值编码，不替代为已核对的工程内容。 */
	private String digest(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest((value == null ? "NULL" : value).getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException failure) {
			throw new IllegalStateException("SHA-256 unavailable", failure);
		}
	}

	private void require(boolean condition, String message) {
		if (!condition) {
			throw new CheckedException(message);
		}
	}
}
