package com.kitex.mom.qms.service.impl;

import static com.kitex.mom.qms.service.impl.QmsInspectionEvidenceChecks.require;
import static com.kitex.mom.qms.service.impl.QmsInspectionPieceService.id;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.qms.dto.QmsInspectionPieceReinspectionDTO.*;
import com.kitex.mom.qms.entity.QmsInspectionProcedureTaskEntity;
import com.kitex.mom.qms.mapper.QmsInspectionPieceMapper;
import com.kitex.mom.qms.mapper.QmsInspectionPieceReinspectionMapper;
import com.kitex.mom.qms.mapper.QmsInspectionPieceReinspectionMapper.Row;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 明确关联已登记的两次样本。只有关系写入，不改变检验结论或认证物理身份。
 */
@Service
@RequiredArgsConstructor
public class QmsInspectionPieceReinspectionService {
	private static final String VERSION = "QMS_PIECE_REINSPECTION@1";
	private final QmsInspectionPieceReinspectionMapper mapper;
	private final QmsInspectionPieceMapper pieceRows;
	private final QmsInspectionPieceService pieces;
	private final QmsReworkCompletionReceiptService receipts;
	private final QmsInspectionHistorySupport access;
	private final QmsInspectionTraceService trace;
	private final PermissionService permissions;

	/**
	 * 同时核对既有工序检验读取许可与当前实际工厂授权，失败时拒绝读取。
	 */
	private void authorize(boolean write) {
		if (!permissions.hasPermission("procedureTask:" + (write ? "submit" : "show"))) {
			throw new AccessDeniedException("没有所需工序检验权限");
		}
		if (write) {
			access.requireWrite();
		} else {
			access.requireRead();
		}
	}

	/**
	 * 核对本域来源结构是否完整安装；未安装返回false，部分就绪时拒绝降级。
	 */
	private boolean installed() {
		int count = mapper.installed();
		if (count == 0) {
			return false;
		}
		require(count == 1 && Objects.equals(mapper.schemaVersion(), 1), "逐件复检关联结构未完整就绪");
		return true;
	}

	/**
	 * 在当前实际组织内读取原检验任务，写入时沿原锁语义锁定；身份或组织不一致时拒绝。
	 */
	private QmsInspectionProcedureTaskEntity parent(Long taskId, boolean lock) {
		var task =
				lock
						? pieceRows.lockParent(
						taskId,
						TenantContextHolder.getTenantId(),
						FactoryContextHolder.getFactoryCode())
						: pieceRows.parent(
						taskId,
						TenantContextHolder.getTenantId(),
						FactoryContextHolder.getFactoryCode());
		require(
				task != null
						&& Objects.equals(task.getTenantId(), TenantContextHolder.getTenantId())
						&& Objects.equals(
						task.getFactoryCode(), FactoryContextHolder.getFactoryCode()),
				"当前实际组织不存在此检验任务");
		return task;
	}

	/**
	 * 在当前实际组织内读取单件永久复检关联，重复或损坏时拒绝。
	 */
	private Row current(Long piece) {
		var rows =
				mapper.byCurrent(
						TenantContextHolder.getTenantId(),
						FactoryContextHolder.getFactoryCode(),
						piece);
		require(rows != null && rows.size() <= 1, "同一实物存在重复复检关联");
		return rows.isEmpty() ? null : rows.get(0);
	}

	/**
	 * 按已核实原状态构造只读投影，不在此授予原首轮或改写样本。
	 */
	private View view(
			String state,
			String taskId,
			String pieceId,
			Proposal proposal,
			List<Evidence> history) {
		return new View(
				VERSION,
				state,
				String.valueOf(TenantContextHolder.getTenantId()),
				FactoryContextHolder.getFactoryCode(),
				taskId,
				pieceId,
				proposal,
				history,
				"UNVERIFIED",
				false);
	}

	/**
	 * 核对实际工厂原单件及永久复检链；已绑定返回原关联，未绑定仅给出原证据建议，不改写样本。
	 *
	 * @param taskId 原检验任务ID字符串
	 * @param pieceId 该任务原单件ID字符串
	 * @return 复检关联或明确的未核实状态
	 */
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public View inspect(String taskId, String pieceId) {
		authorize(false);
		return inspectAuthorized(taskId, pieceId);
	}

	/**
	 * 本域外层已鉴权读取复用入口；仍核对实际组织和完整不可变复检链，不增加人员许可。
	 */
	View inspectAuthorized(String taskId, String pieceId) {
		access.requireScope();
		var task = parent(id(taskId), false);
		Long pieceKey = id(pieceId);
		var own =
				pieceRows.piece(task.getId(), pieceKey, task.getTenantId(), task.getFactoryCode());
		require(own != null, "指定实物不属于当前实际检验任务");
		if (!installed()) {
			return view("SCHEMA_UNAVAILABLE", taskId, pieceId, null, List.of());
		}
		var bound = current(pieceKey);
		if (bound != null) {
			require(Objects.equals(bound.getTaskId(), task.getId()), "实物关联的任务索引不一致");
			return view("BOUND", taskId, pieceId, null, history(bound));
		}
		return proposal(taskId, pieceId);
	}

	/**
	 * 核对实际返工来源、原完工和前次逐件样本后生成复检关联建议；缺少原链保持未核实，不视为初检。
	 */
	private View proposal(String taskId, String pieceId) {
		var current = pieces.checked(id(taskId), id(pieceId));
		trace.inCurrentTransaction("PROCEDURE", id(taskId));
		var incoming =
				mapper.incoming(
						TenantContextHolder.getTenantId(),
						FactoryContextHolder.getFactoryCode(),
						id(taskId));
		require(incoming != null && incoming.size() <= 1, "同一复检任务的返工来源不唯一");
		if (incoming.isEmpty()) {
			require(
					current.task().getSourceRequestId() == null
							|| !current.task()
							.getSourceRequestId()
							.startsWith("REWORK-REINSPECTION:"),
					"复检缺少原返工来源，不能作为初始检验");
			return view("ROOT_UNVERIFIED", taskId, pieceId, null, List.of());
		}
		var order = incoming.get(0);
		require(
				"0".equals(order.getDelFlag())
						&& Objects.equals(order.getTenantId(), TenantContextHolder.getTenantId())
						&& Objects.equals(
						order.getFactoryCode(), FactoryContextHolder.getFactoryCode())
						&& "PROCEDURE".equals(order.getInspectionType()),
				"返工来源已删除或实际组织不一致");
		var receipt = receipts.inCurrentTransaction("PROCEDURE", order.getId());
		if ("LEGACY_UNAVAILABLE".equals(receipt.availability())) {
			return view("LEGACY_RECEIPT_MISSING", taskId, pieceId, null, List.of());
		}
		require(
				"FROZEN".equals(receipt.availability())
						&& receipt.snapshot() != null
						&& Objects.equals(receipt.snapshot().childTaskId(), id(taskId))
						&& Objects.equals(
						receipt.snapshot().originalTaskId(), order.getInspectionTaskId()),
				"原始完工回执的复检来源不一致");
		var candidates =
				pieceRows.byIdentifier(
						order.getInspectionTaskId(),
						current.piece().identifier(),
						TenantContextHolder.getTenantId(),
						FactoryContextHolder.getFactoryCode());
		require(candidates != null && candidates.size() <= 1, "来源任务的实物标识不唯一");
		if (candidates.isEmpty()) {
			return view("SOURCE_NOT_IDENTIFIED", taskId, pieceId, null, List.of());
		}
		var source = pieces.checked(order.getInspectionTaskId(), candidates.get(0).getId());
		var used =
				mapper.bySource(
						TenantContextHolder.getTenantId(),
						FactoryContextHolder.getFactoryCode(),
						source.row().getId());
		require(used != null && used.size() <= 1, "同一前次实物存在重复后续关联");
		if (!used.isEmpty()) {
			return view("SOURCE_ALREADY_USED", taskId, pieceId, null, List.of());
		}
		var command =
				new Command(
						taskId,
						pieceId,
						source.piece().id(),
						String.valueOf(order.getId()),
						receipt.payloadFingerprint(),
						source.row().getBasisFingerprint(),
						current.row().getBasisFingerprint());
		var candidate = new Proposal(command, summary(source), summary(current));
		ancestors(candidate.source(), id(pieceId));
		return view("READY", taskId, pieceId, candidate, List.of());
	}

	/**
	 * 读取并保留原样本、原冻结检验依据和工单工序身份，用于复检链逐项核对。
	 */
	private Summary summary(QmsInspectionPieceService.Checked checked) {
		var piece = checked.piece();
		var task = checked.task();
		require(
				piece.actorId() != null
						&& id(piece.actorId()) > 0
						&& piece.occurredAt() != null
						&& id(piece.occurredAt()) > 0,
				"实物采集审计信息不完整");
		return new Summary(
				String.valueOf(task.getId()),
				piece.id(),
				piece.identifier(),
				task.getOrderNo(),
				task.getProcessCode(),
				checked.row().getBasisFingerprint(),
				piece.actorId(),
				piece.actorName(),
				piece.occurredAt(),
				String.valueOf(piece.samples().size()),
				piece.samples().stream().allMatch(s -> Boolean.TRUE.equals(s.qualified())));
	}

	/**
	 * 校验原复检命令或摘要的ID、指纹和稳定字段，缺失或格式不合法时拒绝。
	 */
	private void valid(Command command) {
		require(command != null, "请提供原逐件复检关联");
		id(command.taskId());
		id(command.pieceId());
		id(command.sourcePieceId());
		id(command.coordinationId());
		require(
				!command.pieceId().equals(command.sourcePieceId())
						&& hash(command.completionFingerprint())
						&& hash(command.sourceBasisFingerprint())
						&& hash(command.currentBasisFingerprint()),
				"逐件复检关联缺少有效原始依据指纹");
	}

	/**
	 * 核对实际组织与原复检建议，在原任务与单件锁下写入永久关联；同事实重放返回原件，同件异事实拒绝，不改写原检验结果。
	 */
	@Transactional(rollbackFor = Exception.class)
	public View bind(Command command) {
		authorize(true);
		valid(command);
		require(installed(), "逐件复检关联尚未启用");
		var task = parent(id(command.taskId()), true);
		var existing = current(id(command.pieceId()));
		if (existing != null) {
			var original = decode(existing);
			require(original.command().equals(command), "此实物已有其他复检关联，请查看原记录");
			return view("BOUND", command.taskId(), command.pieceId(), null, history(existing));
		}
		require("0".equals(task.getDelFlag()), "已删除任务不能新增复检关联");
		var lockedPieces =
				mapper.lockPieces(
						task.getTenantId(),
						task.getFactoryCode(),
						id(command.pieceId()),
						id(command.sourcePieceId()));
		require(
				lockedPieces != null
						&& lockedPieces.size() == 2
						&& new HashSet<>(lockedPieces)
						.equals(Set.of(id(command.pieceId()), id(command.sourcePieceId()))),
				"关联的实物缺失或实际组织不一致");
		var preview = proposal(command.taskId(), command.pieceId());
		require(
				"READY".equals(preview.availability())
						&& preview.proposal().command().equals(command),
				"实际前次检验或完工依据已变化，请核对原关联");
		var user = SecurityUtils.getUser();
		long now = System.currentTimeMillis();
		var sourceRelation = current(id(command.sourcePieceId()));
		var evidence =
				new Evidence(
						VERSION,
						String.valueOf(task.getTenantId()),
						task.getFactoryCode(),
						command,
						preview.proposal().source(),
						preview.proposal().current(),
						sourceRelation == null ? null : sourceRelation.getPayloadFingerprint(),
						String.valueOf(user.getId()),
						cn.hutool.core.util.StrUtil.blankToDefault(
								user.getName(), user.getUsername()),
						String.valueOf(now));
		String payload = QmsInspectionBasisJson.write(evidence);
		require(payload.getBytes(StandardCharsets.UTF_8).length <= 65536, "逐件复检依据超过64KiB");
		var row = new Row();
		row.setId(IdWorker.getId());
		row.setTenantId(task.getTenantId());
		row.setFactoryCode(task.getFactoryCode());
		row.setTaskId(task.getId());
		row.setCurrentPieceId(id(command.pieceId()));
		row.setSourceTaskId(id(evidence.source().taskId()));
		row.setSourcePieceId(id(command.sourcePieceId()));
		row.setCoordinationId(id(command.coordinationId()));
		row.setBoundBy(user.getId());
		row.setBoundAt(now);
		row.setPayload(payload);
		row.setPayloadFingerprint(QmsInspectionBasisJson.sha(payload));
		require(mapper.insert(row) == 1, "逐件复检关联保存失败，未确认关联");
		return view("BOUND", command.taskId(), command.pieceId(), null, history(row));
	}

	/**
	 * 恢复复检关联原文并核对SHA-256、组织、原索引和前次链指纹；损坏或矛盾时拒绝。
	 */
	private Evidence decode(Row row) {
		require(
				row != null
						&& row.getPayload() != null
						&& row.getPayload().getBytes(StandardCharsets.UTF_8).length <= 65536
						&& Objects.equals(
						QmsInspectionBasisJson.sha(row.getPayload()),
						row.getPayloadFingerprint()),
				"逐件复检关联原文指纹不一致");
		var e = QmsInspectionBasisJson.read(row.getPayload(), Evidence.class);
		require(
				e != null
						&& QmsInspectionBasisJson.write(e).equals(row.getPayload())
						&& VERSION.equals(e.schemaVersion())
						&& e.tenantId().equals(String.valueOf(TenantContextHolder.getTenantId()))
						&& e.factoryCode().equals(FactoryContextHolder.getFactoryCode())
						&& Objects.equals(row.getTenantId(), TenantContextHolder.getTenantId())
						&& Objects.equals(
						row.getFactoryCode(), FactoryContextHolder.getFactoryCode()),
				"逐件复检关联原文与实际组织不一致");
		valid(e.command());
		valid(e.source());
		valid(e.current());
		require(
				e.command().taskId().equals(String.valueOf(row.getTaskId()))
						&& e.command().pieceId().equals(String.valueOf(row.getCurrentPieceId()))
						&& e.command()
						.sourcePieceId()
						.equals(String.valueOf(row.getSourcePieceId()))
						&& e.command()
						.coordinationId()
						.equals(String.valueOf(row.getCoordinationId()))
						&& e.source().taskId().equals(String.valueOf(row.getSourceTaskId()))
						&& !e.source().taskId().equals(e.current().taskId())
						&& e.current().taskId().equals(e.command().taskId())
						&& e.current().pieceId().equals(e.command().pieceId())
						&& e.source().pieceId().equals(e.command().sourcePieceId())
						&& e.source().identifier().equals(e.current().identifier())
						&& e.source()
						.basisFingerprint()
						.equals(e.command().sourceBasisFingerprint())
						&& e.current()
						.basisFingerprint()
						.equals(e.command().currentBasisFingerprint())
						&& e.boundBy().equals(String.valueOf(row.getBoundBy()))
						&& e.boundAt().equals(String.valueOf(row.getBoundAt()))
						&& id(e.boundBy()) > 0
						&& id(e.boundAt()) > 0,
				"逐件复检索引、实物或确认人不一致");
		return e;
	}

	/**
	 * 校验原复检命令或摘要的ID、指纹和稳定字段，缺失或格式不合法时拒绝。
	 */
	private void valid(Summary s) {
		require(s != null, "逐件复检缺少留存实物");
		id(s.taskId());
		id(s.pieceId());
		id(s.actorId());
		id(s.collectedAt());
		require(
				s.identifier() != null
						&& !s.identifier().isBlank()
						&& s.identifier().length() <= 200
						&& hash(s.basisFingerprint())
						&& s.sampleCount() != null
						&& s.sampleCount().matches("[1-9][0-9]{0,2}")
						&& Integer.parseInt(s.sampleCount()) <= 500,
				"逐件复检实物或样本数量无效");
	}

	/**
	 * 核对原指纹是否为小写十六进制SHA-256文本。
	 */
	private static boolean hash(String value) {
		return value != null && value.matches("[0-9a-f]{64}");
	}

	/**
	 * 沿不可变前次关联逐项核对完整复检链，缺失、循环或超限时拒绝，不以当前放行覆盖历史。
	 */
	private List<Evidence> history(Row start) {
		var list = new ArrayList<Evidence>();
		var seen = new HashSet<String>();
		Row row = start;
		Summary expected = null;
		while (row != null) {
			require(list.size() < 64, "逐件检验链超过64轮，请先治理，禁止截断");
			var e = decode(row);
			require(
					seen.add(e.current().pieceId())
							&& (expected == null || expected.equals(e.current())),
					"逐件检验链成环或实物留存不一致");
			list.add(e);
			expected = e.source();
			require(!seen.contains(expected.pieceId()), "逐件检验链成环");
			row = current(id(expected.pieceId()));
			require(
					e.sourceRelationFingerprint() == null
							|| (hash(e.sourceRelationFingerprint())
							&& row != null
							&& e.sourceRelationFingerprint()
							.equals(row.getPayloadFingerprint())),
					"逐件检验链缺少已冻结的前次关联或指纹漂移");
		}
		return List.copyOf(list);
	}

	/**
	 * 核对前次复检祖先链，禁止当前件回指自身或构成循环。
	 */
	private void ancestors(Summary source, Long child) {
		require(!source.pieceId().equals(String.valueOf(child)), "实物不能关联自身");
		var prior = current(id(source.pieceId()));
		if (prior != null) {
			var previous = history(prior);
			require(
					previous.size() < 64
							&& previous.get(0).current().equals(source)
							&& previous.stream()
							.noneMatch(
									e ->
											e.current()
													.pieceId()
													.equals(String.valueOf(child))
													|| e.source()
													.pieceId()
													.equals(String.valueOf(child))),
					"逐件检验链超限、成环或来源留存不一致");
		}
	}
}
