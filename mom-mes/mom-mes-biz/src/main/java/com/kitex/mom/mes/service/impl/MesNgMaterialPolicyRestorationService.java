package com.kitex.mom.mes.service.impl;

import com.kitex.mom.mes.dto.MesNgMaterialPolicyDTO;
import com.kitex.mom.mes.mapper.*;
import lombok.RequiredArgsConstructor;
import com.kitex.mom.mes.entity.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;

import java.math.BigDecimal;
import java.util.*;

import static com.kitex.mom.mes.service.impl.MesPhysicalTransformCodec.require;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 独立全份额恢复；不改原NG数量、声明或原耗用证据。
 */
@Service
@RequiredArgsConstructor
public class MesNgMaterialPolicyRestorationService {
	private final MesNgMaterialPolicyMapper ledger;
	private final MesNgMaterialPolicyService operations;
	private final MesTaskReportUnqualifiedMapper reports;
	private final MesTaskArrangeMapper arrangements;
	private final MesWarehouseFeedMapper feeds;
	private final MesWarehouseLiveStockMapper stocks;
	private final MesWarehouseInoutMapper flows;
	private final MesPhysicalTransformSourceScopeLock locks;
	private final MesTerminalExecutionGuard terminal;
	private final MesActualFactoryAccessService access;
	private final MesPhysicalTransformCodec codec;
	private final MesNgMaterialPolicyCodec evidence;
	private final PlatformTransactionManager transactions;

	public static final String COMMAND = "MES_NG_MATERIAL_POLICY_RESTORE_COMMAND_V1";
	private static final Set<String> KEYS = Set.of("version", "requestId", "expectedTenantId", "expectedFactoryCode",
			"expectedActorUserId", "operationId", "expectedFingerprint", "reason");

	/**
	 * 按实际人员和组织独立恢复原账全部份额；raw为已持久化原号的完整命令原文。相同原号返回原回执，异文、另号已恢复或数量冲突拒绝，原NG数量不变。
	 */
	@org.springframework.security.access.prepost.PreAuthorize("@pms.hasPermission('mesTaskReport:materialReverse')")
	@org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NEVER)
	public MesNgMaterialPolicyDTO.Receipt restore(String raw) {
		String factory = access.require("write");
		var command = command(raw, factory);
		return new org.springframework.transaction.support.TransactionTemplate(transactions).execute(status -> execute(raw, command, factory));
	}

	/**
	 * 按原号、实际组织、本人及完整命令查询永久回执；无已提交记录返回ABSENT，未知传输不得据此丢弃本地原文。
	 */
	public MesNgMaterialPolicyDTO.Receipt receipt(String raw) {
		String factory = access.require("read");
		var command = command(raw, factory);
		operations.ready();
		var r = ledger.request(tenant(), text(command, "requestId"));
		if (r == null) {
			return new MesNgMaterialPolicyDTO.Receipt(text(command, "requestId"), "ABSENT", null,
					text(command, "operationId"), tenant().toString(), factory, actor().toString(), COMMAND, raw,
					codec.fingerprint(COMMAND, raw), null, null, null);
		}
		require(Objects.equals(r.getFactoryCode(), factory) && Objects.equals(r.getActorId(), actor())
				&& Objects.equals(r.getCommandSnapshot(), raw), "NG恢复原号已被不同实际工厂、本人或正文占用");
		return verified(r);
	}

	/**
	 * 本号无已提交结果时，另号完整原账是独立状态，不能冒充本号成功。
	 */
	public MesNgMaterialPolicyDTO.Resolution resolve(String raw) {
		String factory = access.require("read");
		var command = command(raw, factory);
		operations.ready();
		var own = ledger.request(tenant(), text(command, "requestId"));
		MesNgMaterialPolicyDTO.Receipt applied = null;
		String state = "ABSENT";
		if (own != null) {
			require(Objects.equals(own.getFactoryCode(), factory) && Objects.equals(own.getActorId(), actor())
					&& Objects.equals(own.getCommandSnapshot(), raw), "NG原号被不同本人、工厂或正文占用");
			applied = verified(own);
			state = "APPLIED";
		} else {
			var h = ledger.operation(tenant(), factory, codec.id(text(command, "operationId")));
			require(h != null && Objects.equals(h.getEvidenceFingerprint(), text(command, "expectedFingerprint")), "原NG耗用不存在或SHA不同");
			evidence.verify(h, ledger.lines(tenant(), factory, h.getId()));
			var other = ledger.attached(tenant(), factory, h.getId());
			if (other != null) {
				applied = verified(other);
				state = "NOT_APPLIED_ALREADY_RESTORED";
			}
		}
		return new MesNgMaterialPolicyDTO.Resolution(text(command, "requestId"), state, tenant().toString(), factory, actor().toString(),
				COMMAND, raw, codec.fingerprint(COMMAND, raw), applied);
	}

	/**
	 * 只接受固定文本字段的原命令，校验实际认证人员与组织；多字段、缺字段、非法原号、SHA或理由均拒绝。
	 */
	private com.fasterxml.jackson.databind.JsonNode command(String raw, String factory) {
		var command = codec.objectDocument(raw, 64 * 1024);
		require(command.size() == KEYS.size(), "NG恢复原文缺少字段或有额外字段");
		for (String key : KEYS) {
			require(command.has(key) && command.get(key).isTextual(), key + "必须是明确文本");
		}
		require(COMMAND.equals(text(command, "version")), "NG恢复协议未知");
		codec.id(text(command, "operationId"));
		codec.reason(text(command, "reason"));
		require(text(command, "requestId").matches("[A-Za-z0-9_-]{8,64}")
				&& text(command, "expectedFingerprint").matches("[a-f0-9]{64}"), "NG恢复原号或原SHA非法");
		require(Objects.equals(codec.id(text(command, "expectedTenantId")), tenant())
				&& Objects.equals(text(command, "expectedFactoryCode"), factory)
				&& Objects.equals(codec.id(text(command, "expectedActorUserId")), actor()), "NG恢复只能使用当前实际实名组织");
		return command;
	}

	/**
	 * 在独立事务内锁定原号与原来源，先冻结全份额恢复清单，再以原余额CAS逐份恢复；任一步失败回滚全部动作。
	 */
	private MesNgMaterialPolicyDTO.Receipt execute(String raw, com.fasterxml.jackson.databind.JsonNode command, String factory) {
		operations.ready();
		require(Boolean.TRUE.equals(ledger.tryRequest(codec.snapshot(List.of(COMMAND, tenant().toString(), text(command, "requestId"))))),
				"NG恢复原号仍在执行，请保留原号查询");
		var replay = ledger.request(tenant(), text(command, "requestId"));
		if (replay != null) {
			require(Objects.equals(replay.getFactoryCode(), factory) && Objects.equals(replay.getActorId(), actor())
					&& Objects.equals(replay.getCommandSnapshot(), raw), "NG恢复原号不能变更本人、工厂或正文");
			return verified(replay);
		}
		var initial = ledger.operation(tenant(), factory, codec.id(text(command, "operationId")));
		require(initial != null, "未找到当前实际组织精确NG POLICY原账");
		locks.task(tenant(), factory, initial.getTaskCode());
		var arrange = arrangements.selectOne(new LambdaQueryWrapper<MesTaskArrangeEntity>()
				.eq(MesTaskArrangeEntity::getTenantId, tenant()).eq(MesTaskArrangeEntity::getFactoryCode, factory)
				.eq(MesTaskArrangeEntity::getCode, initial.getArrangeCode()).last("FOR UPDATE NOWAIT"));
		require(arrange != null && Objects.equals(arrange.getTaskCode(), initial.getTaskCode())
				&& Objects.equals(arrange.getProduceTaskCode(), initial.getProduceTaskCode()), "NG原派工归属不一致");
		terminal.assertUnoccupied(arrange);
		var source = reports.selectOne(new LambdaQueryWrapper<MesTaskReportUnqualifiedEntity>()
				.eq(MesTaskReportUnqualifiedEntity::getTenantId, tenant()).eq(MesTaskReportUnqualifiedEntity::getFactoryCode, factory)
				.eq(MesTaskReportUnqualifiedEntity::getId, initial.getSourceUnqualifiedReportId()).last("FOR SHARE NOWAIT"));
		var h = operations.original(source);
		require(h != null && Objects.equals(h.getId(), initial.getId())
				&& Objects.equals(h.getEvidenceFingerprint(), text(command, "expectedFingerprint")), "NG原账SHA不同，不能猜测恢复");
		require(ledger.attached(tenant(), factory, h.getId()) == null, "该NG原账已全额恢复，不能另号再加回");
		var original = ledger.lines(tenant(), factory, h.getId());
		evidence.verify(h, original);
		var r = new MesNgMaterialPolicyMapper.Reversal();
		r.setId(IdWorker.getId());
		r.setTenantId(tenant());
		r.setFactoryCode(factory);
		r.setOperationId(h.getId());
		r.setActorId(actor());
		r.setRequestId(text(command, "requestId"));
		r.setCommandVersion(COMMAND);
		r.setCommandSnapshot(raw);
		r.setCommandFingerprint(codec.fingerprint(COMMAND, raw));
		var rows = new ArrayList<MesNgMaterialPolicyMapper.Restore>();
		var sourceFeeds = new HashMap<Long, MesWarehouseFeedEntity>();
		var sourceStocks = new HashMap<Long, MesWarehouseLiveStockEntity>();
		var originalFlows = new HashMap<Long, MesWarehouseInoutEntity>();
		var balances = new HashMap<String, BigDecimal>();
		for (var line : original.stream().sorted(Comparator.comparing(MesNgMaterialPolicyMapper.Line::getSourceKind)
				.thenComparing(MesNgMaterialPolicyMapper.Line::getSourceId).thenComparing(MesNgMaterialPolicyMapper.Line::getId)).toList()) {
			var row = new MesNgMaterialPolicyMapper.Restore();
			row.setId(IdWorker.getId());
			row.setTenantId(tenant());
			row.setFactoryCode(factory);
			row.setReversalId(r.getId());
			row.setOperationId(h.getId());
			row.setAllocationId(line.getId());
			row.setSourceKind(line.getSourceKind());
			row.setSourceId(line.getSourceId());
			row.setSourceFlowId(line.getSourceFlowId());
			row.setMatCode(line.getMatCode());
			row.setMatUnit(line.getMatUnit());
			row.setQuantity(line.getQuantity());
			String key = line.getSourceKind() + ":" + line.getSourceId();
			if ("FEED".equals(line.getSourceKind())) {
				var feed = sourceFeeds.computeIfAbsent(line.getSourceId(), id -> feeds.selectOne(new LambdaQueryWrapper<MesWarehouseFeedEntity>()
						.eq(MesWarehouseFeedEntity::getTenantId, tenant()).eq(MesWarehouseFeedEntity::getFactoryCode, factory)
						.eq(MesWarehouseFeedEntity::getId, id).last("FOR UPDATE NOWAIT")));
				require(feed != null && !Boolean.TRUE.equals(feed.getIsLock()) && feed.getUsedNum() != null
								&& codec.objectDocument(line.getSourceSnapshot(), 2 * 1024 * 1024).path("feed")
								.equals(codec.objectDocument(codec.snapshot(operations.feedFacts(feed)), 2 * 1024 * 1024)),
						"原FEED已锁定或来源/单位/总量漂移，不能换份额恢复");
				row.setBeforeQuantity(balances.getOrDefault(key, feed.getUsedNum()));
				row.setAfterQuantity(row.getBeforeQuantity().subtract(line.getQuantity()));
			} else {
				var stock = sourceStocks.computeIfAbsent(line.getSourceId(), id -> ledger.rawStock(tenant(), factory, id));
				require(stock != null && stock.getNum() != null && ("0".equals(stock.getDelFlag())
								|| "1".equals(stock.getDelFlag()) && stock.getNum().signum() == 0)
								&& codec.objectDocument(line.getSourceSnapshot(), 2 * 1024 * 1024).path("stock")
								.equals(codec.objectDocument(codec.snapshot(operations.stockFacts(stock)), 2 * 1024 * 1024)),
						"原STOCK已不存在或来源漂移，禁止合批或用新库存代替");
				var out = flows.selectById(line.getSourceFlowId());
				require(out != null && Objects.equals(out.getTenantId(), tenant()) && Objects.equals(out.getFactoryCode(), factory)
								&& Objects.equals(out.getSourceStockId(), line.getSourceId()) && Objects.equals(out.getBillId(), h.getSourceUnqualifiedReportId())
								&& Objects.equals(out.getBillNo(), h.getArrangeCode()) && Objects.equals(out.getResourceNo(), h.getRequestId())
								&& "out".equals(out.getOperateType()) && "production_ng_material_out".equals(out.getBizType())
								&& Objects.equals(out.getMatCode(), line.getMatCode()) && Objects.equals(out.getMatUnit(), line.getMatUnit())
								&& equal(out.getOperateNum(), line.getQuantity()) && equal(out.getStockBeforeNum(), line.getBeforeQuantity())
								&& equal(out.getStockAfterNum(), line.getAfterQuantity()) && "STOCK_LINE_V1".equals(out.getStockProofVersion()),
						"原NG逐库存OUT不可核验，不能generic入库恢复");
				originalFlows.put(line.getId(), out);
				row.setRestoreFlowId(IdWorker.getId());
				row.setBeforeQuantity(balances.getOrDefault(key, stock.getNum()));
				row.setAfterQuantity(row.getBeforeQuantity().add(line.getQuantity()));
			}
			codec.quantity(codec.amount(row.getAfterQuantity()), true, "NG原份额恢复后余额");
			balances.put(key, row.getAfterQuantity());
			rows.add(row);
		}
		evidence.freezeRestore(r, rows, h, original);
		require(ledger.insertReversal(r) == 1, "NG恢复主账未保存，整笔回滚");
		for (var row : rows) {
			if ("FEED".equals(row.getSourceKind())) {
				var feed = sourceFeeds.get(row.getSourceId());
				require(feeds.update(null, new LambdaUpdateWrapper<MesWarehouseFeedEntity>()
								.eq(MesWarehouseFeedEntity::getTenantId, tenant()).eq(MesWarehouseFeedEntity::getFactoryCode, factory)
								.eq(MesWarehouseFeedEntity::getId, row.getSourceId()).eq(MesWarehouseFeedEntity::getFeedNum, feed.getFeedNum())
								.eq(MesWarehouseFeedEntity::getUsedNum, row.getBeforeQuantity()).set(MesWarehouseFeedEntity::getUsedNum, row.getAfterQuantity())) == 1,
						"原FEED恢复CAS失败，整笔回滚");
				var after = feeds.selectById(row.getSourceId());
				require(after != null && equal(after.getUsedNum(), row.getAfterQuantity())
						&& operations.feedFacts(after).equals(operations.feedFacts(feed)), "原FEED恢复精度或来源回读不一致");
			} else {
				var stock = sourceStocks.get(row.getSourceId());
				require(ledger.restoreStock(tenant(), factory, row.getSourceId(), row.getBeforeQuantity(), row.getAfterQuantity(),
						stock.getDelFlag(), com.kitex.common.security.util.SecurityUtils.getUser().getUsername()) == 1, "原STOCK份额恢复CAS失败，整笔回滚");
				var after = ledger.rawStock(tenant(), factory, row.getSourceId());
				require(after != null && "0".equals(after.getDelFlag()) && equal(after.getNum(), row.getAfterQuantity())
						&& operations.stockFacts(after).equals(operations.stockFacts(stock)), "原STOCK恢复精度或来源回读不一致");
				var flow = new MesWarehouseInoutEntity();
				org.springframework.beans.BeanUtils.copyProperties(originalFlows.get(row.getAllocationId()), flow,
						"id", "createBy", "createTime", "updateBy", "updateTime", "allocationPolicy");
				flow.setId(row.getRestoreFlowId());
				flow.setBillId(r.getId());
				flow.setResourceNo(r.getRequestId());
				flow.setBizType("production_ng_material_restore");
				flow.setOperateType("in");
				flow.setOperateNum(row.getQuantity());
				flow.setStockBeforeNum(row.getBeforeQuantity());
				flow.setStockAfterNum(row.getAfterQuantity());
				flow.setStockProofVersion("NG_POLICY_RESTORE_V1");
				flow.setAllocationPolicy(codec.snapshot(Map.of("version", "NG_POLICY_RESTORE_V1", "operationId", h.getId().toString(),
						"allocationId", row.getAllocationId().toString(), "originalOutId", row.getSourceFlowId().toString(), "stockId", row.getSourceId().toString())));
				flow.setCreateBy(com.kitex.common.security.util.SecurityUtils.getUser().getUsername());
				flow.setDelFlag("0");
				require(flows.insert(flow) == 1, "原STOCK恢复真实IN流水未保存，整笔回滚");
			}
			require(ledger.insertRestore(row) == 1, "原恢复份额未保存，整笔回滚");
		}
		return verified(r);
	}

	/**
	 * 按原账与全体原份额验证恢复typed双向全集和SHA；缺行、重复或来源漂移均拒绝。
	 */
	private MesNgMaterialPolicyDTO.Receipt verified(MesNgMaterialPolicyMapper.Reversal r) {
		var h = ledger.operation(r.getTenantId(), r.getFactoryCode(), r.getOperationId());
		evidence.verifyRestore(r, ledger.restores(r.getTenantId(), r.getFactoryCode(), r.getId()), h,
				ledger.lines(r.getTenantId(), r.getFactoryCode(), r.getOperationId()));
		return new MesNgMaterialPolicyDTO.Receipt(r.getRequestId(), "APPLIED", r.getId().toString(), r.getOperationId().toString(),
				r.getTenantId().toString(), r.getFactoryCode(), r.getActorId().toString(), r.getCommandVersion(), r.getCommandSnapshot(),
				r.getCommandFingerprint(), r.getEvidenceVersion(), r.getEvidenceSnapshot(), r.getEvidenceFingerprint());
	}

	private Long tenant() {
		return com.kitex.common.data.tenant.TenantContextHolder.getTenantId();
	}

	private Long actor() {
		return com.kitex.common.security.util.SecurityUtils.getUser().getId();
	}

	private String text(com.fasterxml.jackson.databind.JsonNode node, String field) {
		return node.path(field).textValue();
	}

	private boolean equal(BigDecimal a, BigDecimal b) {
		return a != null && b != null && a.compareTo(b) == 0;
	}
}
