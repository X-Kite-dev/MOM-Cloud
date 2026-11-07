package com.kitex.mom.mes.service.impl;

import cn.hutool.json.JSONUtil;
import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.dto.MesDispatchAllocationDTO;
import com.kitex.mom.mes.dto.MesIdentifierNodePolicyDTO;
import com.kitex.mom.mes.dto.MesExecutionContractDTO;
import com.kitex.mom.mes.entity.MesDispatchAllocationEntity;
import com.kitex.mom.mes.entity.MesDispatchCommandEntity;
import com.kitex.mom.mes.entity.MesProduceTaskEntity;
import com.kitex.mom.mes.entity.MesExecutionTemplateEntity;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.mapper.MesDispatchAllocationMapper;
import com.kitex.mom.mes.mapper.MesDispatchCorrectionMapper;
import com.kitex.mom.mes.util.MesFrozenExecutionGraph;
import com.kitex.mom.mes.vo.MesDispatchAllocationVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 原ROOT事务内的配额事实与出生原件，不依赖派工服务，供派工编排和原纠错审批共同使用。
 * 配额只约束新派工上限，不替换原GOOD、NG、质量、APS或工程冻结领域事实。
 */
@Component
@RequiredArgsConstructor
public class MesDispatchAllocationLedger {

	private final MesDispatchAllocationMapper mapper;
	private final MesDispatchCorrectionMapper corrections;

	/** 新协议只在第400节表及守卫完整就绪时开放；未部署或守卫漂移均明确拒绝。 */
	public void requireReady() {
		try {
			require(Integer.valueOf(1).equals(mapper.ready()), "派工配额第400节数据库及来源守卫尚未完整就绪");
		} catch (CheckedException failure) {
			throw failure;
		} catch (RuntimeException failure) {
			throw new CheckedException("派工配额第400节数据库及来源守卫尚未完整就绪，请先核对升级", failure);
		}
	}

	/**
	 * 返回已受工单互斥保护的一致配额观察；旧全量历史不回填配额、不静默重解释。
	 * 未核定的旧全量派工使余额未知；严格无执行且CLOSE的历史不占用新额度。
	 */
	public List<MesDispatchAllocationVO.Balance> balances(String taskCode) {
		Source source = source(taskCode);
		var dispatches = mapper.arrangements(tenant(), factory(), taskCode);
		var allocations = mapper.allocations(tenant(), factory(), taskCode);
		var templates = mapper.templates(tenant(), factory());
		Map<Long, MesDispatchAllocationEntity> byArrange = new HashMap<>();
		Map<String, BigDecimal> occupied = new HashMap<>();
		Map<String, String> invalid = new HashMap<>();
		Map<Long, MesTaskArrangeEntity> actualById = new HashMap<>();
		for (var dispatch : dispatches) {
			require(dispatch.getId() != null && actualById.put(dispatch.getId(), dispatch) == null, "配额查询存在重复真实派工");
		}
		for (var allocation : allocations) {
			require(allocation.getArrangeId() != null && byArrange.put(allocation.getArrangeId(), allocation) == null,
					"真实派工存在重复配额原件");
			var operation = source.operations().get(allocation.getProduceTaskCode());
			var actual = actualById.get(allocation.getArrangeId());
			if (operation == null || actual == null
					|| !Objects.equals(allocation.getArrangeCode(), actual.getCode())
					|| !Objects.equals(allocation.getProduceTaskCode(), actual.getProduceTaskCode())
					|| !Objects.equals(allocation.getTaskCode(), actual.getTaskCode())
					|| actual.getNoticeNum() == null || allocation.getAllocatedQuantity() == null
					|| actual.getNoticeNum().compareTo(allocation.getAllocatedQuantity()) != 0
					|| !Objects.equals(allocation.getTaskId(), source.task().getId())
					|| !Objects.equals(allocation.getProduceTaskId(), operation.getId())
					|| !Objects.equals(allocation.getSourceRouteNodeCode(), operation.getSourceRouteNodeCode())
					|| !Objects.equals(allocation.getEngineeringReleaseId(), source.task().getEngineeringReleaseId())
					|| !Objects.equals(allocation.getEngineeringContentHash(), source.task().getEngineeringContentHash())
					|| allocation.getAllocatedQuantity() == null || allocation.getAllocatedQuantity().signum() <= 0
					|| allocation.getOccupiedQuantity() == null || allocation.getOccupiedQuantity().signum() < 0
					|| allocation.getOccupiedQuantity().compareTo(allocation.getAllocatedQuantity()) > 0
					|| allocation.getSettlementStatus() == null
					|| !Set.of("RESERVED", "FINISHED", "UNEXECUTED_CLOSED").contains(allocation.getSettlementStatus())
					|| !"0".equals(allocation.getDelFlag())
					|| "UNEXECUTED_CLOSED".equals(allocation.getSettlementStatus())
							&& (allocation.getOccupiedQuantity().signum() != 0 || allocation.getSettledTime() == null
									|| !"CLOSE".equals(actual.getStatus()))
					|| !"UNEXECUTED_CLOSED".equals(allocation.getSettlementStatus())
							&& allocation.getOccupiedQuantity().compareTo(allocation.getAllocatedQuantity()) != 0
					|| "RESERVED".equals(allocation.getSettlementStatus()) && allocation.getSettledTime() != null
					|| "FINISHED".equals(allocation.getSettlementStatus()) && allocation.getSettledTime() == null) {
				throw new CheckedException("配额原件与冻结工序、工程或真实派工不一致，请先核定");
			}
			occupied.merge(allocation.getProduceTaskCode(), allocation.getOccupiedQuantity(), BigDecimal::add);
		}
		Set<String> legacy = new HashSet<>();
		for (var dispatch : dispatches) {
			require(source.operations().containsKey(dispatch.getProduceTaskCode()), "历史派工缺少真实父工序，不能计算配额");
			if (!byArrange.containsKey(dispatch.getId())) {
				legacy.add(dispatch.getProduceTaskCode());
				if (!unexecutedClosed(dispatch)) {
					invalid.put(dispatch.getProduceTaskCode(), "历史全量派工尚未核定配额，请沿原执行或纠错流程处理");
				}
			}
		}
		List<MesDispatchAllocationVO.Balance> result = new ArrayList<>();
		for (var operation : source.orderedOperations()) {
			var row = new MesDispatchAllocationVO.Balance();
			row.setTaskCode(taskCode);
			row.setProduceTaskCode(operation.getCode());
			row.setRouteName(operation.getRouteName());
			row.setSourceRouteNodeCode(operation.getSourceRouteNodeCode());
			// 选项与余额共用同一已核验工序，避免并读旧泛列表后串用另一组织资源。
			row.setWorksiteCodes(operation.getWorksiteCodes());
			row.setWorksiteNames(operation.getWorksiteNames());
			row.setPlannedWorkerCode(operation.getPlannedWorkerCode());
			row.setPlannedWorkerName(operation.getPlannedWorkerName());
			row.setPlannedWorksiteCode(operation.getPlannedWorksiteCode());
			row.setPlannedWorksiteName(operation.getPlannedWorksiteName());
			row.setPlannedDeviceCode(operation.getPlannedDeviceCode());
			row.setPlannedResourceGroupCode(operation.getPlannedResourceGroupCode());
			row.setDemandQuantity(operation.getNoticeNum());
			row.setLegacyPresent(legacy.contains(operation.getCode()));
			boolean dynamic = dynamic(source.task(), operation, operation.getWorksiteCodes(), templates);
			row.setSplitAllowed(!dynamic);
			String blocked = invalid.get(operation.getCode());
			boolean knownQuantity = blocked == null;
			if (blocked == null && (terminal(source.task().getStatus()) || terminal(operation.getStatus()))) {
				blocked = "已关闭或完工的工单、工序不能由配额补派重新打开";
			}
			if (blocked == null && (source.task().getEngineeringReleaseId() == null
					|| source.task().getEngineeringContentHash() == null || source.task().getEngineeringContentHash().isBlank())) {
				blocked = "原工单缺少精确工程冻结依据";
			}
			BigDecimal used = occupied.getOrDefault(operation.getCode(), BigDecimal.ZERO);
			if (blocked == null && (source.task().getStatus() == null
					|| !Set.of("ISSUE", "DISPATCH", "PRODUCT").contains(source.task().getStatus()))) {
				blocked = "只有已下达且未终结的工单可以配额派工";
			}
			if (blocked == null && dynamic && used.signum() > 0) {
				blocked = "动态绑定区段已派工，继续沿原整组执行，不支持追加配额";
			}
			if (operation.getNoticeNum() == null || operation.getNoticeNum().signum() <= 0
					|| used.compareTo(operation.getNoticeNum()) > 0) {
				blocked = "原工序需求或永久配额占用不可核验，请先核定";
				knownQuantity = false;
			}
			if (blocked == null && used.compareTo(operation.getNoticeNum()) == 0) {
				blocked = "原工序可分配配额已全部占用";
			}
			row.setAllocationAllowed(blocked == null);
			row.setBlockReason(blocked);
			if (knownQuantity) {
				row.setOccupiedQuantity(used);
				row.setAvailableQuantity(operation.getNoticeNum().subtract(used));
			}
			result.add(row);
		}
		return result;
	}

	/** 同ROOT锁内核验全部请求量总和；同工序多行必须一起守恒，不能逐行各读一次旧余额。 */
	public void requireAvailable(MesDispatchAllocationDTO.Command command) {
		Map<String, MesDispatchAllocationVO.Balance> balances = new HashMap<>();
		for (var balance : balances(command.getTaskCode())) {
			balances.put(balance.getProduceTaskCode(), balance);
		}
		Source source = source(command.getTaskCode());
		var templates = mapper.templates(tenant(), factory());
		Map<String, BigDecimal> requested = new HashMap<>();
		for (var line : command.getLines()) {
			requested.merge(line.getProduceTaskCode(), line.getQuantity(), BigDecimal::add);
			var operation = source.operations().get(line.getProduceTaskCode());
			if (operation != null && dynamic(source.task(), operation, line.getWorksiteCodes(), templates)) {
				require(command.getLines().stream().filter(candidate -> Objects.equals(candidate.getProduceTaskCode(),
						line.getProduceTaskCode())).count() == 1 && operation.getNoticeNum() != null
						&& line.getQuantity().compareTo(operation.getNoticeNum()) == 0,
						"动态绑定区段须首次整组唯一全量派工，不能拆分配额或分批追加");
			}
		}
		for (var entry : requested.entrySet()) {
			var balance = balances.get(entry.getKey());
			require(balance != null && Boolean.TRUE.equals(balance.getAllocationAllowed())
					&& balance.getAvailableQuantity() != null, balance == null ? "派工工序不属于原冻结工单"
							: Objects.requireNonNullElse(balance.getBlockReason(), "当前工序不允许配额派工"));
			require(entry.getValue().compareTo(balance.getAvailableQuantity()) <= 0,
					"工序【" + entry.getKey() + "】累计配额超过当前可分配数量，请刷新余额");
		}
	}

	/**
	 * 原builder完成冻结并生成真实ID后、保存实际派工前调用；每一行只出生一次。
	 * 完整回执与逐行配额先写，同事务延迟校验真实派工；builder后续失败会整体回滚。
	 * 命令及配额写入前核每行原冻结配置与配额硬上限可兼容，不读取今日模板重算原策略。
	 *
	 * @param command 原工单本次完整配额命令，分配行顺序与真实派工一一对应
	 * @param rows builder已冻结策略并生成真实ID的待保存派工集合
	 * @return 与完整命令及真实派工集合对应的永久受理回执
	 * @throws CheckedException 配额、冻结配置或出生事实不合法以及必要写失败时拒绝整笔事务
	 */
	@Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
	public MesDispatchAllocationVO.Receipt accept(MesDispatchAllocationDTO.Command command,
			List<MesTaskArrangeEntity> rows) {
		requireTransaction();
		requireAvailable(command);
		Source source = source(command.getTaskCode());
		require(rows != null && rows.size() == command.getLines().size(), "真实派工结果行数与原配额命令不一致");
		for (var row : rows) {
			assertAllocatedDispatch(row);
		}
		var receipt = receipt("ACCEPTED", command, rows);
		MesDispatchCommandEntity original = commandRow(command, receipt, null);
		require(mapper.insertCommand(original) == 1, "派工永久原命令必要写失败，整笔派工回滚");
		Set<Long> ids = new HashSet<>();
		for (int index = 0; index < rows.size(); index++) {
			var actual = rows.get(index);
			var line = command.getLines().get(index);
			var operation = source.operations().get(line.getProduceTaskCode());
			require(actual != null && actual.getId() != null && ids.add(actual.getId())
					&& actual.getCode() != null && !actual.getCode().isBlank()
					&& Objects.equals(actual.getTenantId(), tenant()) && Objects.equals(actual.getFactoryCode(), factory())
					&& Objects.equals(actual.getTaskCode(), command.getTaskCode())
					&& Objects.equals(actual.getProduceTaskCode(), line.getProduceTaskCode())
					&& Objects.equals(actual.getShiftCode(), line.getShiftCode())
					&& csv(actual.getWorkerCode()).equals(csv(line.getWorkerCode()))
					&& csv(actual.getWorksiteCodes()).equals(csv(line.getWorksiteCodes()))
					&& actual.getNoticeNum() != null && actual.getNoticeNum().compareTo(line.getQuantity()) == 0
					&& "UN_RECEIVE".equals(actual.getStatus()) && noExecutionValues(actual),
					"真实派工顺序、资源、配额或零执行出生事实与原命令不一致");
			MesFrozenExecutionGraph.assertArrangement(operation, actual);
			var frozen = JSONUtil.toBean(actual.getExecutionPolicySnapshot(), MesExecutionContractDTO.class);
			if (frozen.getIdentifierNodePolicy() != null
					&& !"NONE".equals(frozen.getIdentifierNodePolicy().getAction())) {
				require(command.getLines().stream().filter(candidate -> Objects.equals(candidate.getProduceTaskCode(),
						line.getProduceTaskCode())).count() == 1 && line.getQuantity().compareTo(operation.getNoticeNum()) == 0,
						"动态绑定区段保持首次整组唯一全量派工，不能拆分配额或分批追加");
			}
			var allocation = new MesDispatchAllocationEntity();
			allocation.setId(IdWorker.getId());
			allocation.setTenantId(tenant());
			allocation.setFactoryCode(factory());
			allocation.setCommandId(original.getId());
			allocation.setAllocationKey(line.getAllocationKey());
			allocation.setTaskId(source.task().getId());
			allocation.setTaskCode(command.getTaskCode());
			allocation.setProduceTaskId(operation.getId());
			allocation.setProduceTaskCode(operation.getCode());
			allocation.setSourceRouteNodeCode(operation.getSourceRouteNodeCode());
			allocation.setEngineeringReleaseId(source.task().getEngineeringReleaseId());
			allocation.setEngineeringContentHash(source.task().getEngineeringContentHash());
			allocation.setArrangeId(actual.getId());
			allocation.setArrangeCode(actual.getCode());
			allocation.setAllocatedQuantity(line.getQuantity());
			allocation.setOccupiedQuantity(line.getQuantity());
			allocation.setSettlementStatus("RESERVED");
			allocation.setCreateBy(original.getActorCode());
			allocation.setCreateTime(original.getCreateTime());
			allocation.setUpdateBy(original.getActorCode());
			allocation.setUpdateTime(original.getCreateTime());
			allocation.setDelFlag("0");
			require(mapper.insertAllocation(allocation) == 1, "真实派工出生配额必要写失败，整笔派工回滚");
		}
		return receipt;
	}

	/**
	 * 原异人审批中给配额原件建立等额替代；旧全量原件仍沿旧纠错合同，不补造历史账。
	 * 调用者保持原323全部门禁；此方法再次核原待审申请与关闭零执行来源，不放宽活动兄弟门。
	 * 仅已有配额原件的本次替代须核冻结数量配置，旧无配额分支不增加配额专属门。
	 *
	 * @param correctionId 当前实际组织的原待审修正申请ID
	 * @param old 本次已受控关闭的原派工，不为无配额旧原件补造配额
	 * @param newRows 本次按原审批冻结、等待真实保存的唯一等额替代派工
	 * @throws CheckedException 原审批、资源、冻结配置或配额来源不符时回滚原修正事务
	 */
	@Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
	public void authorizeCorrection(Long correctionId, MesTaskArrangeEntity old,
			List<MesTaskArrangeEntity> newRows) {
		requireTransaction();
		requireReady();
		require(old != null && correctionId != null, "修正配额必须关联精确原申请及原派工");
		var allocation = mapper.allocation(tenant(), factory(), old.getId());
		if (allocation == null) {
			return;
		}
		settleTerminal(old);
		allocation = mapper.allocation(tenant(), factory(), old.getId());
		var request = corrections.lockedRequest(tenant(), factory(), correctionId);
		var actor = SecurityUtils.getUser();
		require(request != null && "SUBMITTED".equals(request.getStatus())
				&& Objects.equals(request.getArrangeId(), old.getId())
				&& !Objects.equals(request.getApplicantId(), actor.getId())
				&& !Objects.equals(request.getApplicantCode(), actor.getUsername())
				&& "UNEXECUTED_CLOSED".equals(allocation.getSettlementStatus()) && unexecutedClosed(old)
				&& newRows != null && newRows.size() == 1, "只有原异人待审、零执行已关闭且已释放配额可等额修正补派");
		var row = newRows.get(0);
		require(row != null && Objects.equals(row.getCorrectionRequestId(), correctionId)
				&& Objects.equals(row.getProduceTaskCode(), old.getProduceTaskCode())
				&& row.getNoticeNum() != null && row.getNoticeNum().compareTo(allocation.getAllocatedQuantity()) == 0
				&& Objects.equals(row.getShiftCode(), request.getTargetShiftCode())
				&& Objects.equals(row.getShiftName(), request.getTargetShiftName())
				&& csv(row.getWorksiteCodes()).equals(csv(request.getTargetWorksiteCodes()))
				&& Objects.equals(row.getWorkerCode(), old.getWorkerCode())
				&& corrections.otherActive(tenant(), factory(), old.getTaskCode(), old.getProduceTaskCode(), old.getId()) == 0,
				"修正替代派工与原审批资源、原配额或活动兄弟门不一致");
		assertAllocatedDispatch(row);
		var command = new MesDispatchAllocationDTO.Command();
		command.setRequestId("CORRECTION:" + correctionId);
		command.setTaskCode(old.getTaskCode());
		var line = new MesDispatchAllocationDTO.Line();
		line.setAllocationKey("REPLACEMENT:" + correctionId);
		line.setProduceTaskCode(row.getProduceTaskCode());
		line.setShiftCode(row.getShiftCode());
		line.setShiftName(Objects.requireNonNullElse(row.getShiftName(), ""));
		line.setWorkerCode(row.getWorkerCode());
		line.setWorkerName(Objects.requireNonNullElse(row.getWorkerName(), ""));
		line.setWorksiteCodes(row.getWorksiteCodes());
		line.setQuantity(allocation.getAllocatedQuantity());
		command.setLines(List.of(line));
		require(Integer.valueOf(1).equals(mapper.lockRequest(tenant(), factory(), command.getRequestId())), "未取得修正配额原号锁");
		require(mapper.command(tenant(), factory(), command.getRequestId()) == null, "修正配额原号已使用，请核定原审批回执");
		accept(command, newRows);
	}

	/**
	 * 只读取本次builder已冻结的原文，核一次报完与配额硬上限的确定矛盾。
	 *
	 * @param row 本次新配额或已有配额原件的替代派工，必须已有完整执行策略快照
	 * @throws CheckedException 冻结原文缺失、损坏或配置在原配额上不可执行时拒绝整笔事务
	 */
	private void assertAllocatedDispatch(MesTaskArrangeEntity row) {
		require(row != null && row.getExecutionPolicySnapshot() != null
				&& !row.getExecutionPolicySnapshot().isBlank(), "新配额派工缺少原冻结执行策略");
		MesExecutionContractDTO frozen;
		try {
			frozen = JSONUtil.toBean(row.getExecutionPolicySnapshot(), MesExecutionContractDTO.class);
		} catch (RuntimeException failure) {
			throw new CheckedException("新配额派工的冻结执行策略无法解析，原派工事务须回滚", failure);
		}
		require(frozen != null, "新配额派工缺少可核验的原冻结执行策略");
		MesExecutionQuantityCompatibility.assertAllocatedDispatch(frozen.getPartialReportAllowed(),
				frozen.getOverReportAllowed(), frozen.getOverReportRate());
	}

	/**
	 * 原终态写入后结算一次。FINISH永久占原配额；只有目标从未执行CLOSE释放全部配额。
	 * 已执行CLOSE保持RESERVED满占；后续报工冲销或质量变化均不得复活原配额。
	 */
	@Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
	public void settleTerminal(MesTaskArrangeEntity terminal) {
		requireTransaction();
		requireReady();
		require(terminal != null && terminal.getId() != null && Objects.equals(terminal.getTenantId(), tenant())
				&& Objects.equals(terminal.getFactoryCode(), factory()), "配额结算必须使用实际组织原派工");
		var allocation = mapper.allocation(tenant(), factory(), terminal.getId());
		if (allocation == null || !"RESERVED".equals(allocation.getSettlementStatus())) {
			return;
		}
		String status;
		BigDecimal occupied;
		if ("FINISH".equals(terminal.getStatus())) {
			status = "FINISHED";
			occupied = allocation.getAllocatedQuantity();
		} else if (unexecutedClosed(terminal)) {
			status = "UNEXECUTED_CLOSED";
			occupied = BigDecimal.ZERO;
		} else {
			return;
		}
		require(mapper.settle(tenant(), factory(), allocation.getId(), occupied, status, LocalDateTime.now(),
				SecurityUtils.getUser().getUsername()) == 1, "派工终止配额结算未保存，原终止操作回滚");
	}

	/** 构建同组织同操作人的永久原件；调用者已持有原号锁。 */
	public MesDispatchCommandEntity commandRow(MesDispatchAllocationDTO.Command command,
			MesDispatchAllocationVO.Receipt receipt, String reason) {
		var actor = SecurityUtils.getUser();
		var row = new MesDispatchCommandEntity();
		row.setId(IdWorker.getId());
		row.setTenantId(tenant());
		row.setFactoryCode(factory());
		row.setRequestId(command.getRequestId());
		row.setRequestJson(commandJson(command));
		row.setRequestFingerprint(fingerprint(row.getRequestJson()));
		row.setActorId(actor.getId());
		row.setActorCode(actor.getUsername());
		row.setTaskCode(command.getTaskCode());
		row.setStatus(receipt.getStatus());
		row.setResultJson(receiptJson(receipt));
		row.setCancelReason(reason);
		row.setCreateBy(actor.getUsername());
		row.setCreateTime(LocalDateTime.now());
		row.setUpdateBy(actor.getUsername());
		row.setUpdateTime(row.getCreateTime());
		row.setDelFlag("0");
		return row;
	}

	/** 根据原规范命令与真实生成顺序构建不可变行映射，不查询可变生产数量。 */
	public MesDispatchAllocationVO.Receipt receipt(String status, MesDispatchAllocationDTO.Command command,
			List<MesTaskArrangeEntity> rows) {
		var receipt = new MesDispatchAllocationVO.Receipt();
		receipt.setStatus(status);
		receipt.setRequestId(command.getRequestId());
		receipt.setTaskCode(command.getTaskCode());
		receipt.setCommandSnapshot(command);
		receipt.setTenantId(tenant().toString());
		receipt.setFactoryCode(factory());
		receipt.setActorId(SecurityUtils.getUser().getId().toString());
		receipt.setActorCode(SecurityUtils.getUser().getUsername());
		receipt.setCancelReason("");
		List<MesDispatchAllocationVO.LineResult> lines = new ArrayList<>();
		for (int index = 0; index < rows.size(); index++) {
			var actual = rows.get(index);
			var input = command.getLines().get(index);
			require(actual != null && actual.getId() != null, "派工结果缺少真实主键");
			var line = new MesDispatchAllocationVO.LineResult();
			line.setAllocationKey(input.getAllocationKey());
			line.setProduceTaskCode(input.getProduceTaskCode());
			line.setArrangeId(actual.getId().toString());
			line.setArrangeCode(actual.getCode());
			line.setQuantity(input.getQuantity());
			lines.add(line);
		}
		receipt.setLines(lines);
		return receipt;
	}

	/**
	 * 新协议永久原文的唯一规范序列化。所有业务数量保存为普通十进制文本，避免浏览器解析JSON数值损失精度。
	 * 原DTO保留BigDecimal用于领域运算；不能直接用通用Bean序列化替代此方法形成永久指纹。
	 */
	public String commandJson(MesDispatchAllocationDTO.Command command) {
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("requestId", command.getRequestId());
		value.put("taskCode", command.getTaskCode());
		List<Map<String, Object>> lines = new ArrayList<>();
		for (var line : command.getLines()) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("allocationKey", line.getAllocationKey());
			row.put("produceTaskCode", line.getProduceTaskCode());
			row.put("shiftCode", line.getShiftCode());
			row.put("shiftName", line.getShiftName());
			row.put("workerCode", line.getWorkerCode());
			row.put("workerName", line.getWorkerName());
			row.put("worksiteCodes", line.getWorksiteCodes());
			row.put("quantity", line.getQuantity().stripTrailingZeros().toPlainString());
			lines.add(row);
		}
		value.put("lines", lines);
		return JSONUtil.toJsonStr(value);
	}

	/** 数量文本的完整永久回执；包含实名组织、原命令与逐行真实派工映射。 */
	public String receiptJson(MesDispatchAllocationVO.Receipt receipt) {
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("status", receipt.getStatus());
		value.put("requestId", receipt.getRequestId());
		value.put("taskCode", receipt.getTaskCode());
		value.put("commandSnapshot", JSONUtil.parseObj(commandJson(receipt.getCommandSnapshot())));
		List<Map<String, Object>> lines = new ArrayList<>();
		for (var line : receipt.getLines()) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("allocationKey", line.getAllocationKey());
			row.put("produceTaskCode", line.getProduceTaskCode());
			row.put("arrangeId", line.getArrangeId());
			row.put("arrangeCode", line.getArrangeCode());
			row.put("quantity", line.getQuantity().stripTrailingZeros().toPlainString());
			lines.add(row);
		}
		value.put("lines", lines);
		value.put("tenantId", receipt.getTenantId());
		value.put("factoryCode", receipt.getFactoryCode());
		value.put("actorId", receipt.getActorId());
		value.put("actorCode", receipt.getActorCode());
		value.put("cancelReason", receipt.getCancelReason());
		return JSONUtil.toJsonStr(value);
	}

	/** 原文SHA256；实际组织和实名操作人另外由不可变列逐项核验。 */
	public String fingerprint(String text) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(text.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException failure) {
			throw new IllegalStateException(failure);
		}
	}

	/** 只读当前选择规则提示动态整组边界；真实出生还会检查冻结后的原合同。 */
	private boolean dynamic(MesTaskEntity task, MesProduceTaskEntity operation, String worksites,
			List<MesExecutionTemplateEntity> templates) {
		var candidate = new MesTaskArrangeEntity();
		BeanUtil.copyProperties(operation, candidate);
		candidate.setWorksiteCodes(worksites);
		LocalDateTime at = LocalDateTime.now();
		var selected = templates.stream()
				.filter(row -> MesExecutionTemplateMatcher.reasons(row, task, candidate, at).isEmpty())
				.max(MesExecutionTemplateMatcher.order()).orElse(null);
		if (selected == null || selected.getIdentifierNodePolicy() == null
				|| selected.getIdentifierNodePolicy().isBlank()) {
			return false;
		}
		var policy = JSONUtil.toBean(selected.getIdentifierNodePolicy(), MesIdentifierNodePolicyDTO.class);
		return policy.getAction() != null && !"NONE".equals(policy.getAction());
	}

	private Source source(String taskCode) {
		requireReady();
		var tasks = mapper.tasks(tenant(), factory(), taskCode);
		require(tasks.size() == 1, "当前组织生产工单不存在或编码不唯一");
		var operations = mapper.operations(tenant(), factory(), taskCode);
		require(!operations.isEmpty(), "原工单没有可核验的冻结工序");
		MesFrozenExecutionGraph.of(operations);
		Map<String, MesProduceTaskEntity> byCode = new HashMap<>();
		for (var row : operations) {
			require(byCode.put(row.getCode(), row) == null, "真实工序任务编码重复，不能计算配额");
		}
		return new Source(tasks.get(0), operations, byCode);
	}

	private boolean unexecutedClosed(MesTaskArrangeEntity row) {
		return "CLOSE".equals(row.getStatus()) && noExecutionValues(row)
				&& mapper.executionFacts(tenant(), factory(), row.getId(), row.getCode()) == 0;
	}

	private boolean noExecutionValues(MesTaskArrangeEntity row) {
		return row.getStartTime() == null && row.getFinishTime() == null
				&& (row.getFinishHour() == null || row.getFinishHour() == 0)
				&& zero(row.getCompleteNum()) && zero(row.getQualityReleasedNum()) && zero(row.getUnPassNum());
	}

	private boolean zero(BigDecimal value) {
		return value != null && value.signum() == 0;
	}

	private boolean terminal(String status) {
		return status != null && Set.of("CLOSE", "FINISH").contains(status);
	}

	private Set<String> csv(String value) {
		Set<String> result = new HashSet<>();
		if (value != null) {
			for (String part : value.split(",")) {
				if (!part.isBlank()) {
					result.add(part.trim());
				}
			}
		}
		return result;
	}

	private void requireTransaction() {
		require(TransactionSynchronizationManager.isActualTransactionActive(), "配额出生或结算必须处于原工单事务");
	}

	private Long tenant() {
		Long tenant = TenantContextHolder.getTenantId();
		require(tenant != null && tenant > 0 && !TenantContextHolder.getTenantSkip(), "配额缺少实际租户");
		return tenant;
	}

	private String factory() {
		String factory = FactoryContextHolder.getFactoryCode();
		require(factory != null && !factory.isBlank() && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()),
				"配额缺少实际工厂");
		return factory;
	}

	private void require(boolean condition, String message) {
		if (!condition) {
			throw new CheckedException(message);
		}
	}

	private record Source(MesTaskEntity task, List<MesProduceTaskEntity> orderedOperations,
			Map<String, MesProduceTaskEntity> operations) {
	}
}
