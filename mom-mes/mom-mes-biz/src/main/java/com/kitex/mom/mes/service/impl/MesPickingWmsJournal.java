package com.kitex.mom.mes.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.dto.MesPickingWmsV1DTO;
import com.kitex.mom.mes.entity.MesWarehousePickingBillEntity;
import com.kitex.mom.mes.mapper.MesPickingWmsV1Mapper;
import com.kitex.mom.wms.dto.WmsProductionNoticeAcceptanceDTO.*;
import com.kitex.mom.wms.entity.WmsOutNoticeEntity;
import com.kitex.mom.wms.entity.WmsOutNoticeDetailEntity;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static com.kitex.mom.mes.service.impl.MesPickingWmsCanonical.*;

/**
 * 短本域事务负责准备、永久证明及来源投影；本类绝不调用WMS网络。
 */
@Service
@RequiredArgsConstructor
public class MesPickingWmsJournal {
	private final MesPickingWmsV1Mapper mapper;
	private final MesPickingWmsSourceGuard sources;
	private final MesActualFactoryAccessService access;
	private final MesMaterialExecutionGuard materialExecution;
	private final MesPickingMaterialPolicy policy;
	private final PermissionService permissions;

	/**
	 * 只读当前/历史修订；历史及已删除原件不开放任何操作。
	 */
	@Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
	public MesPickingWmsV1DTO.Context context(Long id, Integer revision) {
		String factory = access.require("read");
		Long tenant = TenantContextHolder.getTenantId();
		var bill = mapper.bill(tenant, factory, id);
		require(bill != null && id != null && id > 0, "当前租户工厂未找到原领料单");
		boolean ready = sources.available();
		var source = ready ? mapper.source(tenant, factory, id) : null;
		int current = source == null ? 0 : source.getCurrentRevision();
		int selected = revision == null ? current : revision;
		require(selected >= 0 && selected <= current, "所选来源修订不存在");
		boolean history = selected != current;
		List<MesPickingWmsV1Mapper.Command> commands =
				source == null || selected == 0 ? List.of() : mapper.commands(tenant, factory, id, selected);
		require(commands.size() <= 2, "原修订命令集合不完整或重复");
		String mode = !ready ? "UNAVAILABLE" : source == null ? "LEGACY" : "V1";
		String phase = source == null ? mode : source.getPhase();
		boolean deleted = !"0".equals(bill.getDelFlag());
		boolean factoryWritable = false;
		if (!history && !deleted && source != null && permissions.hasPermission("mes_mesWarehousePickingBill_edit")) {
			try {
				factoryWritable = factory.equals(access.require("write"));
			} catch (RuntimeException denied) {
				factoryWritable = false;
			}
		}
		boolean writable = factoryWritable && !history && !deleted && source != null;
		var active = commands.stream()
				.filter(command -> command.getRequestId().equals(source == null ? "" : source.getCurrentRequestId()))
				.findFirst().orElse(null);
		boolean owner = active != null && Objects.equals(active.getOperatorId(), SecurityUtils.getUser().getId());
		boolean pending = Set.of("PREPARED", "CANCELLING").contains(phase);
		boolean canPrepare = writable && Set.of("DRAFT", "CANCELLED").contains(phase) && "0".equals(bill.getStatus());
		boolean canCancel = writable && Set.of("PREPARED", "ACCEPTED").contains(phase)
				&& Set.of("0", "1").contains(bill.getStatus()) && mapper.issued(tenant, factory, id) == 0;
		String blocked = "";
		if (!ready) {
			blocked = "MES领料V1完整结构或在途守卫未就绪";
		} else if (source == null) {
			blocked = "历史领料沿原接口办理，不能自动收编V1";
		} else if (deleted) {
			blocked = "原领料已逻辑删除，永久命令仅供审计";
		} else if (history) {
			blocked = "历史修订仅供只读核对";
		} else if (!writable) {
			blocked = "当前账号没有领料维护或实际工厂写权限，或权限暂时不可核验";
		} else if (pending && !owner) {
			blocked = "未决原操作只能由原操作者继续核对";
		} else if (pending) {
			blocked = "原命令待核对；通知受理不等于发料或签收";
		}
		return new MesPickingWmsV1DTO.Context(1, mode, tenant.toString(), factory,
				SecurityUtils.getUser().getId().toString(),
				id.toString(), bill.getBillNo(), bill.getTaskCode(), current, selected, phase, history,
				commands.stream().map(MesPickingWmsProof::view).toList(), canPrepare,
				writable && pending && owner && "PREPARED".equals(active.getState()), writable && pending && owner,
				canCancel, blocked);
	}

	/**
	 * 准备原命令时核准本域生产许可并冻结全文；SQL在途守卫与hold使用同根互斥。
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
	public MesPickingWmsV1DTO.Context prepare(MesPickingWmsV1DTO.Prepare input) {
		require(input != null && input.billId() != null && input.action() != null &&
				Set.of("SUBMIT", "CANCEL").contains(input.action()), "请选择原领料及准确操作");
		String factory = access.require("write");
		sources.requireReady();
		Long tenant = TenantContextHolder.getTenantId();
		var observed = mapper.source(tenant, factory, input.billId());
		if (observed != null) {
			var repeated = observed.getCurrentRevision() == 0 ? List.<MesPickingWmsV1Mapper.Command>of()
					: mapper.commands(tenant, factory, input.billId(), observed.getCurrentRevision());
			var original = repeated.stream().filter(command -> input.action().equals(command.getAction())).findFirst()
					.orElse(null);
			boolean sameRound =
					"SUBMIT".equals(input.action()) && Set.of("PREPARED", "ACCEPTED").contains(observed.getPhase())
							|| "CANCEL".equals(input.action()) &&
							Set.of("CANCELLING", "CANCELLED").contains(observed.getPhase());
			if (sameRound && original != null) {
				require(Objects.equals(original.getOperatorId(), SecurityUtils.getUser().getId()),
						"该原操作由另一操作者登记");
				var originalFrozen = MesPickingWmsProof.frozen(original);
				int expected = "SUBMIT".equals(input.action()) ?
						observed.getCurrentRevision() - 1 : observed.getCurrentRevision();
				require(input.expectedPreviousRevision() == null ?
								expected == 0 : input.expectedPreviousRevision() == expected,
						"请按原命令的前修订核对，不得覆盖已有原件");
				if ("CANCEL".equals(input.action())) {
					require(text(input.reason()).equals(originalFrozen.cancel().getReason()), "原撤回原因不能覆盖");
				}
				return context(input.billId(), null);
			}
		}
		var bill = materialExecution.picking(input.billId());
		var source = mapper.source(tenant, factory, input.billId());
		require(source != null && "0".equals(bill.getDelFlag()), "仅已登记的未删除V1领料可以准备命令");
		identity(source, bill);
		int previous = source.getCurrentRevision();
		if (input.expectedPreviousRevision() != null) {
			require(input.expectedPreviousRevision() == previous, "来源修订已变化，请重新核对原命令");
		} else {
			require(previous == 0, "请明确本次原来源修订");
		}
		boolean submit = "SUBMIT".equals(input.action());
		boolean repeat = submit && Set.of("PREPARED", "ACCEPTED").contains(source.getPhase())
				|| !submit && Set.of("CANCELLING", "CANCELLED").contains(source.getPhase());
		if (repeat) {
			var existing = mapper.commands(tenant, factory, input.billId(), previous).stream()
					.filter(command -> input.action().equals(command.getAction())).findFirst().orElse(null);
			require(existing != null && Objects.equals(existing.getOperatorId(), SecurityUtils.getUser().getId()),
					"原动作已由另一操作者登记，请保留原号");
			var frozen = MesPickingWmsProof.frozen(existing);
			if (!submit) {
				require(text(input.reason()).equals(text(frozen.cancel().getReason())), "原撤回原因已经冻结，不能覆盖");
			}
			return context(input.billId(), null);
		}
		int revision = submit ? previous + 1 : previous;
		require(revision > 0 && revision <= 1000000, "来源修订已超出支持范围");
		MesPickingWmsProof.Frozen frozen;
		String request = "MPW-" + UUID.randomUUID().toString().replace("-", "");
		if (submit) {
			require(Set.of("DRAFT", "CANCELLED").contains(source.getPhase()) && "0".equals(bill.getStatus()),
					"原来源未完成撤回或不是可提交草稿");
			var details = mapper.details(tenant, factory, bill.getId());
			require(!details.isEmpty() && details.size() <= 500, "领料明细须为1至500行");
			bill.setDetailList(details);
			try {
				policy.normalize(bill, true);
			} catch (ArithmeticException failure) {
				throw new CheckedException("领料BOM需求当量无法按8位小数准确表达，禁止舍入提交", failure);
			}
			frozen = submission(bill, revision, previous);
		} else {
			require(Set.of("PREPARED", "ACCEPTED").contains(source.getPhase()), "当前来源不允许准备撤回");
			require(Set.of("0", "1").contains(bill.getStatus()) && mapper.issued(tenant, factory, bill.getId()) == 0,
					"已有WMS发料或MES签收事实，请沿退料处置，不能撤回来源");
			Cancel cancel = new Cancel();
			cancel.setRequestId(request);
			cancel.setTenantId(tenant);
			cancel.setFactoryCode(factory);
			cancel.setDocumentId(bill.getId());
			cancel.setSourceRevision(revision);
			cancel.setReason(text(input.reason()));
			cancelPayload(cancel, new Scope(tenant, factory));
			frozen = new MesPickingWmsProof.Frozen(1, "CANCEL", List.of(), cancel);
		}
		var row = new MesPickingWmsV1Mapper.Command();
		row.setTenantId(tenant);
		row.setFactoryCode(factory);
		row.setBillId(bill.getId());
		row.setRequestId(request);
		row.setSourceRevision(revision);
		row.setAction(input.action());
		row.setOperatorId(SecurityUtils.getUser().getId());
		row.setOperatorName(SecurityUtils.getUser().getUsername());
		row.setPayload(write(frozen));
		row.setPayloadSha256(hash(row.getPayload()));
		row.setCreateTime(LocalDateTime.now());
		row.setUpdateTime(row.getCreateTime());
		row.setState("PREPARED");
		MesPickingWmsProof.frozen(row);
		require(mapper.insertCommand(row) == 1, "原领料命令未可靠保存");
		require(mapper.phase(tenant, factory, bill.getId(), previous, source.getPhase(), revision,
				submit ? "PREPARED" : "CANCELLING", request) == 1, "来源阶段推进失败，原命令已回滚");
		return context(bill.getId(), null);
	}

	/**
	 * 精确构造完整分仓源文；每条原行单独关联，不从父单ID推导。
	 */
	private MesPickingWmsProof.Frozen submission(MesWarehousePickingBillEntity bill, int revision, int previous) {
		var groups = bill.getDetailList().stream()
				.collect(Collectors.groupingBy(detail -> detail.getWarehouseCode(), TreeMap::new, Collectors.toList()));
		require(groups.size() <= 20, "一次来源提交最多20仓");
		List<Command> commands = new ArrayList<>();
		for (var group : groups.entrySet()) {
			WmsOutNoticeEntity notice = new WmsOutNoticeEntity();
			BeanUtil.copyProperties(bill, notice, "id", "detailList");
			notice.setBillType("produce");
			notice.setDocumentTable(SOURCE);
			notice.setDocumentId(bill.getId());
			notice.setWarehouseCode(group.getKey());
			notice.setWarehouseName(group.getValue().get(0).getWarehouseName());
			List<WmsOutNoticeDetailEntity> lines = new ArrayList<>();
			for (var detail : group.getValue()) {
				require(detail.getId() != null && detail.getId() > 0 &&
						Objects.equals(detail.getBillId(), bill.getId()), "原领料明细归属不完整");
				double quantity = detail.getMatNum().doubleValue();
				require(Double.isFinite(quantity) &&
								java.math.BigDecimal.valueOf(quantity).compareTo(detail.getMatNum()) == 0,
						"现有WMS接口不能无损表达该物料数量，禁止舍入");
				WmsOutNoticeDetailEntity line = new WmsOutNoticeDetailEntity();
				BeanUtil.copyProperties(detail, line, "id");
				line.setDocumentId(bill.getId());
				line.setDocumentTable(SOURCE);
				line.setSourceDocumentDetailId(detail.getId());
				line.setNoticeOutNum(quantity);
				lines.add(line);
			}
			notice.setDetailList(lines);
			Command command = new Command();
			command.setRequestId("MPW-" + UUID.randomUUID().toString().replace("-", ""));
			command.setTenantId(bill.getTenantId());
			command.setFactoryCode(bill.getFactoryCode());
			command.setSourceRevision(revision);
			command.setExpectedPreviousRevision(previous == 0 ? null : previous);
			command.setNotice(notice);
			command.setNotice(
					MesPickingWmsCanonical.prepare(command, new Scope(bill.getTenantId(), bill.getFactoryCode()), false)
							.notice());
			commands.add(command);
		}
		return new MesPickingWmsProof.Frozen(1, "SUBMIT", commands, null);
	}

	/**
	 * 无业务锁加载本人原号供网络运输；已终态原件不重授生产写许可。
	 */
	@Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
	public MesPickingWmsV1Mapper.Command load(MesPickingWmsV1DTO.Request request, boolean transport) {
		require(request != null && request.billId() != null, "请选择原领料命令");
		request(request.requestId());
		String factory = access.require("write");
		sources.requireReady();
		var bill = mapper.bill(TenantContextHolder.getTenantId(), factory, request.billId());
		require(bill != null, "原领料不存在或组织不符");
		var row = mapper.command(TenantContextHolder.getTenantId(), factory, request.billId(), request.requestId());
		require(row != null && Objects.equals(row.getOperatorId(), SecurityUtils.getUser().getId()),
				"原操作不存在或不是本人原请求");
		MesPickingWmsProof.frozen(row);
		var source = mapper.source(row.getTenantId(), factory, row.getBillId());
		require(source != null, "V1原来源缺失");
		identity(source, bill);
		if (transport && !"ACCEPTED".equals(row.getState())) {
			require("0".equals(bill.getDelFlag()) && row.getRequestId().equals(source.getCurrentRequestId())
							&& Set.of("PREPARED", "CANCELLING").contains(source.getPhase()),
					"原操作已被后续撤回或修订替代，只允许查询原回执");
		}
		return row;
	}

	/**
	 * 已知同修订完整SUBMIT凭据，用于约束CANCEL的真实通知集合。
	 */
	public List<Receipt> knownSubmission(MesPickingWmsV1Mapper.Command row) {
		return mapper.commands(row.getTenantId(), row.getFactoryCode(), row.getBillId(), row.getSourceRevision())
				.stream()
				.filter(command -> "SUBMIT".equals(command.getAction()) && "ACCEPTED".equals(command.getState()))
				.findFirst()
				.map(command -> MesPickingWmsProof.verify(command, MesPickingWmsProof.receipts(command), List.of()))
				.orElse(List.of());
	}

	/**
	 * 证明独立可靠保存；不在这里取新生产许可或改源阶段，失败仍保留原未决命令。
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
	public void record(MesPickingWmsV1DTO.Request request, List<Receipt> proof) {
		var row = load(request, false);
		String receipt = write(MesPickingWmsProof.verify(row, proof, knownSubmission(row)));
		if ("ACCEPTED".equals(row.getState())) {
			require(receipt.equals(row.getReceipt()) && hash(receipt).equals(row.getReceiptSha256()),
					"已存永久回执与原证明不一致");
			return;
		}
		require(mapper.accept(row.getTenantId(), row.getFactoryCode(), row.getBillId(), row.getRequestId(), receipt,
						hash(receipt)) == 1,
				"领料永久回执保存未确认，请继续按原号核对");
	}

	/**
	 * 仅尚未应用的当前原件需新生产准入；已应用重放只观察，晚到旧证明不回退取消/新修订。
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
	public MesPickingWmsV1DTO.Context apply(MesPickingWmsV1DTO.Request request) {
		var row = load(request, false);
		require("ACCEPTED".equals(row.getState()), "原命令尚未取得完整永久证明");
		MesPickingWmsProof.verify(row, MesPickingWmsProof.receipts(row), knownSubmission(row));
		var source = mapper.source(row.getTenantId(), row.getFactoryCode(), row.getBillId());
		if (!row.getRequestId().equals(source.getCurrentRequestId()) ||
				!Set.of("PREPARED", "CANCELLING").contains(source.getPhase())) {
			return context(row.getBillId(), row.getSourceRevision());
		}
		var bill = materialExecution.picking(row.getBillId());
		source = mapper.source(row.getTenantId(), row.getFactoryCode(), row.getBillId());
		identity(source, bill);
		if (!row.getRequestId().equals(source.getCurrentRequestId()) ||
				!Set.of("PREPARED", "CANCELLING").contains(source.getPhase())) {
			return context(row.getBillId(), row.getSourceRevision());
		}
		boolean submit = "SUBMIT".equals(row.getAction());
		if (submit && "0".equals(bill.getStatus())) {
			require(mapper.billStatus(row.getTenantId(), row.getFactoryCode(), row.getBillId(), "0", "1") == 1,
					"受理后的领料本域状态未更新");
		} else if (!submit) {
			require(mapper.issued(row.getTenantId(), row.getFactoryCode(), row.getBillId()) == 0
					&& Set.of("0", "1").contains(bill.getStatus()), "已有实发/签收事实，不能回退领料状态");
			if ("1".equals(bill.getStatus())) {
				require(mapper.billStatus(row.getTenantId(), row.getFactoryCode(), row.getBillId(), "1", "0") == 1,
						"来源撤回后的本域状态未更新");
			}
		}
		require(mapper.phase(row.getTenantId(), row.getFactoryCode(), row.getBillId(), source.getCurrentRevision(),
						source.getPhase(),
						source.getCurrentRevision(), submit ? "ACCEPTED" : "CANCELLED", row.getRequestId()) == 1,
				"永久证明已保存，本域应用尚未确认");
		return context(row.getBillId(), null);
	}

	/**
	 * 根已锁后复核永久source与真实原单，不能将同号新单或换工单当原件。
	 */
	private void identity(MesPickingWmsV1Mapper.Source source, MesWarehousePickingBillEntity bill) {
		require(Objects.equals(source.getBillId(), bill.getId()) && Objects.equals(source.getBillNo(), bill.getBillNo())
						&& Objects.equals(source.getTaskCode(), bill.getTaskCode()) &&
						Objects.equals(source.getTenantId(), bill.getTenantId())
						&& Objects.equals(source.getFactoryCode(), bill.getFactoryCode())
						&& mapper.taskIdentity(source.getTenantId(), source.getFactoryCode(), source.getTaskId(),
						source.getTaskCode()) == 1,
				"V1来源与实际领料或原工单身份不一致");
	}
}
