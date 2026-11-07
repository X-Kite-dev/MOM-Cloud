package com.kitex.mom.wms.service.inventoryapproval;

import com.kitex.mom.wms.dto.*;
import com.kitex.mom.wms.dto.WmsInventoryApprovalRecoveryDTO.*;
import com.kitex.mom.wms.entity.WmsInBillDetailEntity;
import com.kitex.mom.wms.entity.WmsInventoryAdjustmentApprovalEntity;
import com.kitex.mom.wms.service.WmsWarehouseBusinessGuard;
import com.kitex.mom.wms.service.impl.WmsInventoryAdjustmentApprovalService;
import com.kitex.mom.wms.service.impl.WmsInventoryPlanAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;

import static com.kitex.mom.wms.service.impl.WmsInventoryPlanAccess.require;
import static com.kitex.mom.wms.service.inventoryapproval.WmsInventoryApprovalRecoveryStore.str;

/**
 * 同一本地事务内仲裁原号、受托原审批动作并冻结数据库真实事件；不直接修改审批状态。
 */
@Service
@RequiredArgsConstructor
public class WmsInventoryApprovalRecoveryService {
	private final WmsInventoryApprovalRecoveryCodec codec;
	private final WmsInventoryApprovalRecoveryAccess access;
	private final WmsInventoryApprovalRecoveryStore store;
	private final WmsInventoryPlanAccess plans;
	private final WmsInventoryAdjustmentApprovalService approvals;
	private final WmsWarehouseBusinessGuard guard;

	/**
	 * 原真实范围、展示元数据与当前能力；已过账或开关关闭仍可进入恢复。
	 */
	@Transactional(readOnly = true)
	public Context context(String detailId, boolean mobile) {
		var identity = access.identity();
		var row = access.detail(identity, detailId);
		Scope scope = new Scope(identity.tenant().toString(), identity.actor().toString(), identity.factory(),
				str(row, "warehouse_code"), str(row, "plan_id"), detailId);
		access.bind(identity, scope);
		require(str(row, "plan_no") != null && !str(row, "plan_no").isBlank()
				&& str(row, "mat_code") != null && !str(row, "mat_code").isBlank(), "原计划号或物料编码缺失");
		String warehouse = scope.warehouseCode();
		boolean enabled = guard.mobileEnabled(warehouse);
		boolean canEnd = access.executable(warehouse) && access.factory(identity, "write");
		boolean business = businessEnabled(warehouse);
		return new Context(1, scope, identity.actorCode(), str(row, "plan_no"), str(row, "mat_code"),
				str(row, "mat_name"),
				str(row, "mat_unit"), enabled, canEnd && business && (!mobile || enabled), canEnd,
				"IA1_" + identity.actor() + "_" + UUID.randomUUID().toString().replace("-", ""));
	}

	/**
	 * 只做静态规范化、真实范围与岗位验证，不能提前执行审批或分配业务编码。
	 */
	@Transactional(readOnly = true)
	public Envelope prepare(Envelope prototype) {
		Envelope prepared = codec.prepare(prototype);
		Command c = codec.command(prepared);
		var identity = access.identity();
		access.bind(identity, c.scope());
		access.write(identity, c.scope().warehouseCode());
		return prepared;
	}

	/**
	 * 纯查询永久原件；数据库或权限异常原样拒绝，不冒充未受理。
	 */
	@Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
	public Resolution lookup(Envelope e) {
		Command c = codec.command(e);
		var identity = access.identity();
		access.bind(identity, c.scope());
		return store.resolution(store.find(c.scope(), e.requestId()), e, c);
	}

	/**
	 * 新执行先取租户原号锁，再进入原计划→明细→审批锁；已有终态优先回读。
	 */
	@Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
	public Resolution execute(Envelope e, boolean mobile) {
		return mutate(e, mobile, false);
	}

	/**
	 * 结束只封当前原号，须同仓执行岗与实际厂写权；不依赖今日业务、移动或审批余额。
	 */
	@Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
	public Resolution end(Envelope e) {
		return mutate(e, false, true);
	}

	private Resolution mutate(Envelope e, boolean mobile, boolean ending) {
		Command c = codec.command(e);
		var identity = access.identity();
		require(identity.tenant().toString().equals(c.scope().tenantId()) &&
				identity.actor().toString().equals(c.scope().actorId())
				&& identity.factory().equals(c.scope().factoryCode()), "原号身份范围不一致");
		store.lock(c.scope(), e.requestId());
		access.bind(identity, c.scope());
		var previous = store.find(c.scope(), e.requestId());
		if (previous != null) {
			return store.resolution(previous, e, c);
		}
		store.ready();
		access.write(identity, c.scope().warehouseCode());
		if (ending) {
			store.save(e, c, identity.actorCode(), null);
		} else {
			// 直到原号锁之后才允许原access.detail取得计划/明细行锁，避免和end倒序死锁。
			var row = plans.detail(Long.valueOf(c.scope().planDetailId()));
			require(row.getPlanId().toString().equals(c.scope().planId()) &&
					row.getWarehouseCode().equals(c.scope().warehouseCode()), "原计划范围已变化");
			require(businessEnabled(c.scope().warehouseCode()), "实际仓未启用盘点业务");
			if (mobile) {
				guard.checkMobile(c.scope().warehouseCode());
			}
			Long target = c.review() == null ? null : Long.valueOf(c.review().approvalId());
			if (target != null) {
				store.approval(c.scope(), target);
			}
			store.intent(e.requestId(), c.action(), c.scope().actorId());
			WmsInventoryAdjustmentApprovalEntity applied;
			try {
				applied = WmsInventoryApprovalExecutionContext.call(e.requestId(), c.action(), target, c.scope(),
						() -> apply(e, c, mobile));
			} finally {
				store.intent("", "", "");
			}
			var event = store.approval(c.scope(), applied.getId());
			store.save(e, c, identity.actorCode(), event);
		}
		return store.resolution(store.find(c.scope(), e.requestId()), e, c);
	}

	private boolean businessEnabled(String warehouse) {
		return guard.evaluate(warehouse, "other_inventory", true).allowed()
				&& guard.evaluate(warehouse, "other_inventory", false).allowed();
	}

	/**
	 * 只构造旧正式合同，所有动态选料、差异策略、异岗和状态校验仍在原服务执行。
	 */
	private WmsInventoryAdjustmentApprovalEntity apply(Envelope e, Command c, boolean mobile) {
		if (c.submit() != null) {
			Submit s = c.submit();
			var legacy = new WmsInventoryAdjustmentApprovalSubmit();
			legacy.setRequestId(e.requestId());
			legacy.setPlanDetailId(Long.valueOf(c.scope().planDetailId()));
			legacy.setExpectedCurrentNumber(Double.valueOf(s.expectedCurrentNumber()));
			legacy.setExpectedInventoryNum(Double.valueOf(s.expectedInventoryNum()));
			legacy.setReason(s.reason());
			legacy.setOwnershipConfirmed(s.ownershipConfirmed());
			legacy.setDetail(detail(s.detail()));
			legacy.setLossStocks(s.lossStocks().stream()
					.map(x -> new WmsInventoryLossStockRequest(x.stockId(), x.quantity(), x.basisVersion())).toList());
			return mobile ? approvals.submitMobile(legacy) : approvals.submit(legacy);
		}
		var legacy = new WmsInventoryAdjustmentApprovalReview();
		legacy.setApprovalId(Long.valueOf(c.review().approvalId()));
		legacy.setDecision(c.action());
		legacy.setComment(c.review().comment());
		return mobile ? approvals.reviewMobile(legacy) : approvals.review(legacy);
	}

	private WmsInBillDetailEntity detail(Detail d) {
		var entity = new WmsInBillDetailEntity();
		entity.setMatBatch(d.matBatch());
		entity.setSupplierCode(d.supplierCode());
		entity.setSupplierName(d.supplierName());
		entity.setContainerCode(d.containerCode());
		entity.setMatBarcode(d.matBarcode());
		entity.setMatProduceDate(d.matProduceDate() == null ? null : LocalDate.parse(d.matProduceDate()));
		entity.setMatValidPeriod(d.matValidPeriod());
		entity.setMatValidEndDate(d.matValidEndDate() == null ? null : LocalDate.parse(d.matValidEndDate()));
		entity.setStockOwnerType(d.stockOwnerType());
		entity.setStockOwnerCode(d.stockOwnerCode());
		entity.setSourceBusinessNo(d.sourceBusinessNo());
		entity.setMatUnit(d.matUnit());
		return entity;
	}
}
