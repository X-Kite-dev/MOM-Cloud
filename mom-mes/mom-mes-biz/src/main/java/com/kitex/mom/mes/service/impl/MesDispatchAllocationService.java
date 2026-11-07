package com.kitex.mom.mes.service.impl;

import cn.hutool.json.JSONUtil;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.dto.MesDispatchAllocationDTO;
import com.kitex.mom.mes.entity.MesDispatchCommandEntity;
import com.kitex.mom.mes.mapper.MesDispatchAllocationMapper;
import com.kitex.mom.mes.service.MesIdentifierBindingGroupService;
import com.kitex.mom.mes.service.MesTaskArrangeService;
import com.kitex.mom.mes.vo.MesDispatchAllocationVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 普通派工配额原命令的纯事务编排；原builder仍持有工程、APS、人员工位及执行策略冻结规则。
 * 同原号先核永久结果再检查当前门；原已受理事实不因后续关单、修订hold或配置变化失效。
 */
@Service
@RequiredArgsConstructor
public class MesDispatchAllocationService {

	private final MesActualFactoryAccessService factoryAccess;
	private final MesDispatchAllocationMapper mapper;
	private final MesDispatchAllocationLedger ledger;
	private final MesTaskArrangeService arrangements;
	private final MesIdentifierBindingGroupService taskLocks;
	private final MesProductionTaskMutex productionMutex;

	/**
	 * 在原ROOT互斥与修订hold内整体受理1至200行，保证SUM新配额不超过原冻结工序剩余额度。
	 * 返回永久原回执；已取消的原号直接返回取消原件，不生成任何派工。
	 */
	@Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
	public MesDispatchAllocationVO.Receipt execute(MesDispatchAllocationDTO.Command input) {
		return executeNormalized(normalize(input, false));
	}

	/** 原组合服务在外原号及排序ROOT锁内调用，服务端派生保留子号，不接受客户端任意指定。 */
	@Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
	public MesDispatchAllocationVO.Receipt executeForComposition(String outerRequestId, String taskCode,
			List<MesDispatchAllocationDTO.Line> lines) {
		return executeNormalized(compositionCommand(outerRequestId, taskCode, lines));
	}

	/** 组合原文与执行共用的纯归一入口；只生成确定子号及规范行，不查询或写入业务。 */
	public MesDispatchAllocationDTO.Command compositionCommand(String outerRequestId, String taskCode,
			List<MesDispatchAllocationDTO.Line> lines) {
		var command = new MesDispatchAllocationDTO.Command();
		command.setRequestId(compositionRequestId(outerRequestId, taskCode));
		command.setTaskCode(taskCode);
		command.setLines(lines);
		return normalize(command, true);
	}

	/** 只读原组合逐工单子回执；历史旧运行没有子配额原件时返回ABSENT。 */
	@Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
	public MesDispatchAllocationVO.Receipt compositionReceipt(String outerRequestId, String taskCode) {
		return receipt(compositionRequestId(outerRequestId, taskCode));
	}

	/** 同一外原号和真实工单只生成同一子号；数组编码消除文本拼接的歧义。 */
	private String compositionRequestId(String outerRequestId, String taskCode) {
		String outer = requestId(outerRequestId, true);
		String code = text(taskCode, 100, "生产工单编码", false);
		return "PC-" + ledger.fingerprint(JSONUtil.toJsonStr(List.of(outer, code)));
	}

	private MesDispatchAllocationVO.Receipt executeNormalized(MesDispatchAllocationDTO.Command command) {
		String factory = factoryAccess.require("write");
		ledger.requireReady();
		Long tenant = TenantContextHolder.getTenantId();
		require(Integer.valueOf(1).equals(mapper.lockRequest(tenant, factory, command.getRequestId())), "未取得派工原号锁");
		var original = mapper.command(tenant, factory, command.getRequestId());
		if (original != null) {
			return replay(original, command);
		}
		// 与报工、工程修订及既有派工共用原工单ROOT锁和修订hold，不能只锁新配额表。
		taskLocks.lockDispatchTask(command.getTaskCode());
		ledger.requireAvailable(command);
		AtomicReference<MesDispatchAllocationVO.Receipt> accepted = new AtomicReference<>();
		var rows = arrangements.arrangeAllocated(command.getTaskCode(), command.getLines(), actual -> {
			require(accepted.get() == null, "真实派工builder重复触发出生回调");
			accepted.set(ledger.accept(command, actual));
		});
		require(accepted.get() != null && rows != null && rows.size() == command.getLines().size(),
				"真实派工builder没有完成逐行配额出生，整笔派工回滚");
		return accepted.get();
	}

	/** 同ROOT锁内读取权威余额观察；执行时仍需重新加锁核定，页面旧余额不是预占许可。 */
	@Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
	public List<MesDispatchAllocationVO.Balance> balance(String taskCode) {
		factoryAccess.require("read");
		String code = text(taskCode, 100, "生产工单编码", false);
		productionMutex.lock(code);
		return ledger.balances(code);
	}

	/**
	 * 当前实际组织和原实名操作人的永久回执；ABSENT不证明请求从未到达，禁止据此换号重投。
	 * 此读取不会申请修订hold的写许可，hold期间仍可核对原结果。
	 */
	@Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
	public MesDispatchAllocationVO.Receipt receipt(String requestId) {
		String factory = factoryAccess.require("read");
		ledger.requireReady();
		String request = requestId(requestId, true);
		var original = mapper.command(TenantContextHolder.getTenantId(), factory, request);
		if (original != null) {
			return replay(original, null);
		}
		var absent = new MesDispatchAllocationVO.Receipt();
		absent.setStatus("ABSENT");
		absent.setRequestId(request);
		absent.setTenantId(TenantContextHolder.getTenantId().toString());
		absent.setFactoryCode(factory);
		absent.setActorId(SecurityUtils.getUser().getId().toString());
		absent.setActorCode(SecurityUtils.getUser().getUsername());
		absent.setCancelReason("");
		absent.setLines(List.of());
		return absent;
	}

	/**
	 * 原号锁内永久作废尚未受理原文；已受理时回原派工，不能将取消页面等同于撤销派工。
	 * 墓碑与受理使用同一表、同一原号唯一约束，因此并发迟到提交不能再生成派工。
	 */
	@Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
	public MesDispatchAllocationVO.Receipt cancel(MesDispatchAllocationDTO.Cancel input) {
		String factory = factoryAccess.require("write");
		ledger.requireReady();
		require(input != null, "请提供待作废完整原命令及原因");
		var command = normalize(input.getCommand(), false);
		String reason = text(input.getReason(), 500, "原号作废原因", false);
		Long tenant = TenantContextHolder.getTenantId();
		require(Integer.valueOf(1).equals(mapper.lockRequest(tenant, factory, command.getRequestId())), "未取得派工原号锁");
		var original = mapper.command(tenant, factory, command.getRequestId());
		if (original != null) {
			return replay(original, command);
		}
		var cancelled = ledger.receipt("CANCELLED", command, List.of());
		cancelled.setCancelReason(reason);
		var row = ledger.commandRow(command, cancelled, reason);
		require(mapper.insertCommand(row) == 1, "原号永久作废凭证必要写失败，请保留原命令核对");
		return cancelled;
	}

	private MesDispatchAllocationVO.Receipt replay(MesDispatchCommandEntity original,
			MesDispatchAllocationDTO.Command command) {
		var actor = SecurityUtils.getUser();
		require(Objects.equals(original.getTenantId(), TenantContextHolder.getTenantId())
				&& Objects.equals(original.getActorId(), actor.getId())
				&& original.getRequestJson() != null
				&& Objects.equals(ledger.fingerprint(original.getRequestJson()), original.getRequestFingerprint())
				&& Objects.equals(original.getActorCode(), actor.getUsername())
				&& "0".equals(original.getDelFlag()), "请回原实际组织及操作人账号核对派工原号");
		if (command != null) {
			require(Objects.equals(original.getTaskCode(), command.getTaskCode())
					&& Objects.equals(original.getRequestFingerprint(), ledger.fingerprint(ledger.commandJson(command))),
					"该派工原号已用于另一完整正文，请保留原命令核对");
		}
		try {
			var result = JSONUtil.toBean(original.getResultJson(), MesDispatchAllocationVO.Receipt.class);
			require(result != null && Set.of("ACCEPTED", "CANCELLED").contains(result.getStatus())
					&& Objects.equals(result.getStatus(), original.getStatus())
					&& Objects.equals(result.getRequestId(), original.getRequestId())
					&& Objects.equals(result.getTaskCode(), original.getTaskCode())
					&& Objects.equals(result.getTenantId(), original.getTenantId().toString())
					&& Objects.equals(result.getFactoryCode(), original.getFactoryCode())
					&& Objects.equals(result.getActorId(), original.getActorId().toString())
					&& Objects.equals(result.getActorCode(), original.getActorCode())
					&& result.getCommandSnapshot() != null && result.getLines() != null
					&& Objects.equals(ledger.fingerprint(ledger.commandJson(result.getCommandSnapshot())),
							original.getRequestFingerprint()), "派工永久回执与原命令或实名组织不一致，请核定原件");
			verifyReceiptLines(original, result);
			return result;
		} catch (CheckedException failure) {
			throw failure;
		} catch (RuntimeException failure) {
			throw new CheckedException("派工永久回执无法解析，请保留原号核定原件", failure);
		}
	}

	/** 回执只采用原永久行映射，结构或精确配额损坏时不能结束客户端未知原号。 */
	private void verifyReceiptLines(MesDispatchCommandEntity original, MesDispatchAllocationVO.Receipt result) {
		require(Objects.equals(result.getCancelReason(), Objects.requireNonNullElse(original.getCancelReason(), "")),
				"派工永久作废原因与原件不一致");
		if ("CANCELLED".equals(result.getStatus())) {
			require(result.getLines().isEmpty(), "原号作废回执不应存在真实派工结果");
			return;
		}
		var inputs = result.getCommandSnapshot().getLines();
		require(inputs != null && inputs.size() == result.getLines().size(), "原派工回执行集合不完整");
		Set<String> ids = new HashSet<>();
		Set<String> codes = new HashSet<>();
		for (int index = 0; index < inputs.size(); index++) {
			var input = inputs.get(index);
			var line = result.getLines().get(index);
			require(input != null && line != null && Objects.equals(input.getAllocationKey(), line.getAllocationKey())
					&& Objects.equals(input.getProduceTaskCode(), line.getProduceTaskCode())
					&& input.getQuantity() != null && line.getQuantity() != null
					&& input.getQuantity().compareTo(line.getQuantity()) == 0
					&& line.getArrangeId() != null && line.getArrangeId().matches("[1-9][0-9]{0,18}")
					&& ids.add(line.getArrangeId()) && line.getArrangeCode() != null && !line.getArrangeCode().isBlank()
					&& codes.add(line.getArrangeCode()), "原派工逐行稳定标识、真实派工或精确配额不一致");
		}
	}

	/** 规范化完整输入形成永久原文；不接受调用方改写来源、工程或任何真实执行字段。 */
	private MesDispatchAllocationDTO.Command normalize(MesDispatchAllocationDTO.Command input, boolean internal) {
		require(input != null && input.getLines() != null && !input.getLines().isEmpty()
				&& input.getLines().size() <= 200, "请选择1至200条真实派工配额行");
		var command = new MesDispatchAllocationDTO.Command();
		command.setRequestId(requestId(input.getRequestId(), internal));
		command.setTaskCode(text(input.getTaskCode(), 100, "生产工单编码", false));
		List<MesDispatchAllocationDTO.Line> lines = new ArrayList<>();
		Set<String> keys = new HashSet<>();
		for (var inputLine : input.getLines()) {
			require(inputLine != null, "派工配额行不能为空");
			var line = new MesDispatchAllocationDTO.Line();
			line.setAllocationKey(text(inputLine.getAllocationKey(), 100, "稳定分配行标识", false));
			require(keys.add(line.getAllocationKey()), "同一原命令中的稳定分配行标识不能重复");
			line.setProduceTaskCode(text(inputLine.getProduceTaskCode(), 100, "真实工序任务编码", false));
			line.setShiftCode(text(inputLine.getShiftCode(), 100, "派工班次编码", false));
			line.setShiftName(text(inputLine.getShiftName(), 100, "派工班次名称", true));
			line.setWorkerCode(csv(inputLine.getWorkerCode(), "执行人员工号", 1000));
			line.setWorkerName(text(inputLine.getWorkerName(), 1000, "执行人员姓名", true));
			line.setWorksiteCodes(csv(inputLine.getWorksiteCodes(), "授权工位编码", 2000));
			BigDecimal quantity = inputLine.getQuantity();
			require(quantity != null && quantity.signum() > 0 && quantity.stripTrailingZeros().scale() <= 6
					&& quantity.compareTo(new BigDecimal("100000000000000")) < 0,
					"每行配额必须是大于0、最多六位小数且不超过数量精度的精确数值");
			line.setQuantity(quantity.stripTrailingZeros());
			lines.add(line);
		}
		command.setLines(lines);
		return command;
	}

	private String requestId(String input, boolean allowInternal) {
		require(input != null && input.matches("[A-Za-z0-9_.:-]{8,100}")
				&& (allowInternal || !input.startsWith("CORRECTION:") && !input.startsWith("PC-")), "请提供8至100位稳定派工原号；纠错原号由原审批生成");
		return input;
	}

	private String csv(String input, String label, int maxLength) {
		String value = text(input, maxLength, label, false);
		Set<String> parts = new LinkedHashSet<>();
		for (String part : value.split(",", -1)) {
			require(!part.isBlank() && parts.add(part.trim()), label + "不能为空或重复");
		}
		return String.join(",", parts);
	}

	private String text(String input, int maxLength, String label, boolean optional) {
		String value = input == null ? "" : input.trim();
		require((optional || !value.isBlank()) && value.length() <= maxLength
				&& value.chars().noneMatch(Character::isISOControl), label + "为空、过长或含控制字符");
		return value;
	}

	private void require(boolean condition, String message) {
		if (!condition) {
			throw new CheckedException(message);
		}
	}
}
