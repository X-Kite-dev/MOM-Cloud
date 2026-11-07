package com.kitex.mom.wms.service.impl;

import org.springframework.beans.factory.annotation.Autowired;
import com.kitex.mom.wms.service.impl.externalinventory.ExternalLossReceipt;
import com.kitex.mom.wms.service.impl.externalinventory.ExternalLossContext;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.wms.dto.WmsInventoryAdjustmentCommand;
import com.kitex.mom.wms.entity.*;
import com.kitex.mom.wms.mapper.*;
import com.kitex.mom.wms.service.inBill.DefaultWmsInBillService;
import com.kitex.mom.wms.service.outBill.DefaultWmsOutBillService;
import com.kitex.mom.wms.service.WmsWarehouseBusinessGuard;
import com.kitex.mom.wms.util.WmsStockOwnership;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

import static com.kitex.mom.wms.service.impl.WmsInventoryPlanAccess.*;

/**
 * A plan detail is the permanent operation identity: lock, book once, then mark complete in the same transaction.
 */
@Service
@RequiredArgsConstructor
public class WmsInventoryAdjustmentService {
	private final WmsInventoryPlanAccess access;
	private final WmsInsideInventoryPlanDetailMapper details;
	private final WmsLiveStockMapper stocks;
	private final WmsInBillMapper inBills;
	private final WmsOutBillMapper outBills;
	private final WmsInBillDetailMapper inDetails;
	private final WmsOutBillDetailMapper outDetails;
	private final DefaultWmsInBillService inbound;
	private final DefaultWmsOutBillService outbound;
	private final WmsInventoryAdjustmentApprovalService approvals;
	private final WmsWarehouseBusinessGuard warehouseGuard;
	private final WmsInventoryStockCheckService stockChecks;
	private final WmsInventoryMovementReceipt movementReceipt;
	private final WmsInventoryLossSelectionService lossSelection;

	@Autowired
	private ExternalLossReceipt externalReceipt;

	/**
	 * 仅受托POST可创建外部指定库存盘亏单，单头校验与真实出库服务都在同一事务。
	 */
	public void postExternal(WmsOutBillEntity bill) {
		ExternalLossContext.checkBill(bill);
		WmsInventoryAdjustmentContext.execute(bill.getDocumentId(), () -> {
			require(Boolean.TRUE.equals(outbound.addAndPass(bill)), "外部盘亏出库失败");
			return true;
		});
	}

	@Transactional(rollbackFor = Exception.class)
	public boolean adjust(WmsInventoryAdjustmentCommand command) {
		return adjust(command, false);
	}

	@Transactional(rollbackFor = Exception.class)
	public boolean adjustMobile(WmsInventoryAdjustmentCommand command) {
		return adjust(command, true);
	}

	private boolean adjust(WmsInventoryAdjustmentCommand command, boolean mobile) {
		require(command != null, "盘点损益命令不能为空");
		var row = access.detail(command.getPlanDetailId());
		var plan = access.plan(row.getPlanId(), true);
		require("1".equals(plan.getStatus()), "请先完成整张盘点计划的实盘录入");
		require(Objects.equals(plan.getWarehouseCode(), row.getWarehouseCode())
						&& StrUtil.isNotBlank(row.getMatCode()) && StrUtil.isNotBlank(row.getSiteCode())
						&& StrUtil.isNotBlank(row.getMatUnit()),
				"原盘点计划与明细仓库或物料库位范围不完整/不一致，请先人工核对");
		require("1".equals(row.getStatus()), "请先完成该明细的实盘录入");
		var current = number(row.getCurrentNumber(), "原账面数量");
		var counted = number(row.getInventoryNum(), "实盘数量");
		require(current.compareTo(number(command.getExpectedCurrentNumber(), "页面账面数量")) == 0
						&& counted.compareTo(number(command.getExpectedInventoryNum(), "页面实盘数量")) == 0,
				"盘点数量已变化，请刷新原任务后重新核对");
		BigDecimal delta = counted.subtract(current);
		String billNo = "IC-" + row.getId();
		var existingIn = inBills.selectList(Wrappers.<WmsInBillEntity>lambdaQuery()
				.eq(WmsInBillEntity::getDocumentId, row.getId())
				.eq(WmsInBillEntity::getDocumentTable, WmsInventoryAdjustmentContext.DOCUMENT)
				.eq(WmsInBillEntity::getTenantId, TenantContextHolder.getTenantId())
				.eq(WmsInBillEntity::getFactoryCode, factory()).last("FOR UPDATE"));
		var existingOut = outBills.selectList(Wrappers.<WmsOutBillEntity>lambdaQuery()
				.eq(WmsOutBillEntity::getDocumentId, row.getId())
				.eq(WmsOutBillEntity::getDocumentTable, WmsInventoryAdjustmentContext.DOCUMENT)
				.eq(WmsOutBillEntity::getTenantId, TenantContextHolder.getTenantId())
				.eq(WmsOutBillEntity::getFactoryCode, factory()).last("FOR UPDATE"));
		if ("1".equals(row.getPlStatus())) {
			requireReceipt(row);
			return true;
		}
		if (mobile) {
			warehouseGuard.checkMobile(plan.getWarehouseCode());
		}
		WmsInventoryAdjustmentApprovalEntity approved = delta.signum() == 0
				? null : approvals.requireApproved(row, command);
		require(!"1".equals(plan.getHandleState()), "该盘点计划已完成，不能追加调账");
		require(existingIn.isEmpty() && existingOut.isEmpty(), "该明细已有未核定损益单据，请先核对，禁止重复调账");
		// Reject drift instead of replacing today's balance with an old count. Existing rows stay locked until commit.
		var scope = stocks.selectList(Wrappers.<WmsLiveStockEntity>lambdaQuery()
				.eq(WmsLiveStockEntity::getTenantId, TenantContextHolder.getTenantId())
				.eq(WmsLiveStockEntity::getFactoryCode, factory()).eq(WmsLiveStockEntity::getMatCode, row.getMatCode())
				.eq(WmsLiveStockEntity::getWarehouseCode, row.getWarehouseCode())
				.eq(WmsLiveStockEntity::getSiteCode, row.getSiteCode())
				.eq(StrUtil.isNotBlank(row.getMatBatch()), WmsLiveStockEntity::getMatBatch, row.getMatBatch())
				.orderByAsc(WmsLiveStockEntity::getId).last("FOR UPDATE"));
		for (var stock : scope) {
			require(Objects.equals(row.getMatUnit(), stock.getMatUnit()),
					"盘点范围实时库存单位与原计划不一致，请先核对");
			if (delta.signum() != 0) {
				require(WmsStockOwnership.isCompanyOwned(stock),
						"盘点差异涉及客供或寄售库存，禁止按自有库存自动调账，请人工核对");
			}
		}
		BigDecimal balance = scope.stream().map(s -> number(s.getMatNum(), "实时库存数量")).reduce(BigDecimal.ZERO,
				BigDecimal::add);
		require(balance.compareTo(current) == 0, "盘点后库存已发生变化，请先核对库存及原实盘依据，禁止直接按旧差异调账");
		if (delta.signum() == 0) {
			stockChecks.requireOwnerBalanced(row, scope);
		}
		if (delta.signum() != 0) {
			var frozen = WmsInventorySelectionSnapshot.read(row, approved.getSelectedDetailJson(), true);
			lossSelection.requireCurrent(row, frozen, scope);
			WmsInBillDetailEntity selected = frozen.detail();
			require(selected != null, "请选择损益批次及物料属性");
			if (delta.signum() > 0) {
				require(StrUtil.isNotBlank(selected.getMatBatch()) && StrUtil.isNotBlank(selected.getSupplierCode())
								&& StrUtil.isNotBlank(selected.getSupplierName())
								&& selected.getMatProduceDate() != null,
						"盘盈必须明确批次、供应商和生产日期");
			}
			require(StrUtil.isBlank(row.getMatBatch()) || Objects.equals(row.getMatBatch(), selected.getMatBatch()),
					"损益批次与原盘点范围不一致");
			WmsInBillDetailEntity material = material(row, selected, delta.abs().doubleValue());
			WmsInventoryAdjustmentContext.execute(row.getId(), () -> {
				if (delta.signum() > 0) {
					var bill = new WmsInBillEntity();
					bill.setBillNo(billNo);
					bill.setBillType("other_inventory");
					bill.setWarehouseCode(plan.getWarehouseCode());
					bill.setWarehouseName(plan.getWarehouseName());
					bill.setFactoryCode(factory());
					bill.setDocumentTable(WmsInventoryAdjustmentContext.DOCUMENT);
					bill.setDocumentId(row.getId());
					bill.setDetails(List.of(material));
					require(Boolean.TRUE.equals(inbound.addAndPass(bill)), "盘盈入库失败");
				} else {
					var detail = new WmsOutBillDetailEntity();
					BeanUtils.copyProperties(material, detail, "id", "billId");
					var bill = new WmsOutBillEntity();
					bill.setBillNo(billNo);
					bill.setBillType("other_inventory");
					bill.setWarehouseCode(plan.getWarehouseCode());
					bill.setWarehouseName(plan.getWarehouseName());
					bill.setFactoryCode(factory());
					bill.setDocumentTable(WmsInventoryAdjustmentContext.DOCUMENT);
					bill.setDocumentId(row.getId());
					if (frozen.lossStocks().isEmpty()) {
						bill.setDetails(List.of(detail));
					} else {
						bill.setDetails(frozen.lossStocks().stream().map(source -> {
							var exact = new WmsOutBillDetailEntity();
							var physical = material(row, source.detail().material(),
									WmsInventoryLossSnapshot.quantity(source.quantity()).doubleValue());
							BeanUtils.copyProperties(physical, exact, "id", "billId");
							exact.setSourceStockId(WmsInventoryLossSnapshot.id(source.stockId()));
							return exact;
						}).toList());
					}
					require(Boolean.TRUE.equals(outbound.addAndPass(bill)), "盘亏出库失败");
				}
				return true;
			});
			requireReceipt(row, frozen, true);
		}
		row.setPlStatus("1");
		require(details.updateById(row) == 1, "盘点损益状态保存失败，本次调账已回滚");
		approvals.markPosted(approved);
		return true;
	}

	public void requireOwnerBalancedForCompletion(WmsInsideInventoryPlanDetailEntity row,
	                                              List<WmsLiveStockEntity> lockedStocks) {
		stockChecks.requireOwnerBalanced(row, lockedStocks);
	}

	public void requireReceipt(WmsInsideInventoryPlanDetailEntity row) {
		var external = approvals.postedExternal(row);
		if (external != null) {
			externalReceipt.posted(row, external);
			return;
		}
		var delta = number(row.getInventoryNum(), "实盘数量").subtract(number(row.getCurrentNumber(), "账面数量"));
		requireReceipt(row, delta.signum() == 0 ? null : approvals.postedSelection(row), false);
	}

	private void requireReceipt(WmsInsideInventoryPlanDetailEntity row,
	                            WmsInventorySelectionSnapshot.Frozen frozen, boolean firstPosting) {
		BigDecimal delta = number(row.getInventoryNum(), "实盘数量").subtract(
				number(row.getCurrentNumber(), "账面数量"));
		var receiptsIn = inBills.selectList(Wrappers.<WmsInBillEntity>lambdaQuery()
				.eq(WmsInBillEntity::getDocumentId, row.getId())
				.eq(WmsInBillEntity::getDocumentTable, WmsInventoryAdjustmentContext.DOCUMENT)
				.eq(WmsInBillEntity::getTenantId, TenantContextHolder.getTenantId())
				.eq(WmsInBillEntity::getFactoryCode, factory()).last("FOR UPDATE"));
		var receiptsOut = outBills.selectList(Wrappers.<WmsOutBillEntity>lambdaQuery()
				.eq(WmsOutBillEntity::getDocumentId, row.getId())
				.eq(WmsOutBillEntity::getDocumentTable, WmsInventoryAdjustmentContext.DOCUMENT)
				.eq(WmsOutBillEntity::getTenantId, TenantContextHolder.getTenantId())
				.eq(WmsOutBillEntity::getFactoryCode, factory()).last("FOR UPDATE"));
		if (delta.signum() == 0) {
			require(receiptsIn.isEmpty() && receiptsOut.isEmpty(), "零差异明细存在损益单据，请先核对");
			return;
		}
		String number = "IC-" + row.getId();
		BigDecimal total;
		if (delta.signum() > 0) {
			require(receiptsIn.size() == 1 && receiptsOut.isEmpty(), "历史盘盈缺少唯一回执，请人工核对，禁止重复调账");
			var bill = receiptsIn.get(0);
			require(number.equals(bill.getBillNo()) && "other_inventory".equals(bill.getBillType())
							&& "2".equals(bill.getState()),
					"盘盈回执来源或状态不一致，请人工核对");
			var items = inDetails.selectList(Wrappers.<WmsInBillDetailEntity>lambdaQuery()
					.eq(WmsInBillDetailEntity::getBillId, bill.getId())
					.eq(WmsInBillDetailEntity::getTenantId, TenantContextHolder.getTenantId())
					.eq(WmsInBillDetailEntity::getFactoryCode, factory()).last("FOR UPDATE"));
			require(!items.isEmpty() && items.stream().allMatch(d -> Objects.equals(row.getMatCode(), d.getMatCode())
					&& Objects.equals(row.getSiteCode(), d.getSiteCode())
					&& (StrUtil.isBlank(row.getMatBatch()) || Objects.equals(row.getMatBatch(), d.getMatBatch()))
					&& Objects.equals(row.getWarehouseCode(), d.getWarehouseCode())), "盘盈回执范围不一致");
			if (frozen != null) {
				items.forEach(d -> WmsInventorySelectionSnapshot.requireReceipt(row, frozen, d, true));
				movementReceipt.requireMovement(row, frozen, bill.getId(), items, true, firstPosting);
			}
			total = items.stream().map(d -> number(d.getMatNum(), "盘盈数量")).reduce(BigDecimal.ZERO, BigDecimal::add);
		} else {
			require(receiptsOut.size() == 1 && receiptsIn.isEmpty(), "历史盘亏缺少唯一回执，请人工核对，禁止重复调账");
			var bill = receiptsOut.get(0);
			require(number.equals(bill.getBillNo()) && "other_inventory".equals(bill.getBillType())
							&& "2".equals(bill.getState()),
					"盘亏回执来源或状态不一致，请人工核对");
			var items = outDetails.selectList(Wrappers.<WmsOutBillDetailEntity>lambdaQuery()
					.eq(WmsOutBillDetailEntity::getBillId, bill.getId())
					.eq(WmsOutBillDetailEntity::getTenantId, TenantContextHolder.getTenantId())
					.eq(WmsOutBillDetailEntity::getFactoryCode, factory()).last("FOR UPDATE"));
			require(!items.isEmpty() && items.stream().allMatch(d -> Objects.equals(row.getMatCode(), d.getMatCode())
					&& Objects.equals(row.getSiteCode(), d.getSiteCode())
					&& (StrUtil.isBlank(row.getMatBatch()) || Objects.equals(row.getMatBatch(), d.getMatBatch()))
					&& Objects.equals(row.getWarehouseCode(), d.getWarehouseCode())), "盘亏回执范围不一致");
			if (frozen != null) {
				WmsInventoryLossSnapshot.requireOutDetails(frozen, items);
				items.forEach(d -> {
					var receipt = new WmsInBillDetailEntity();
					BeanUtils.copyProperties(d, receipt);
					WmsInventorySelectionSnapshot.requireReceipt(row, frozen, receipt, false);
				});
				var physical = items.stream().map(d -> {
					var receipt = new WmsInBillDetailEntity();
					BeanUtils.copyProperties(d, receipt);
					return receipt;
				}).toList();
				movementReceipt.requireMovement(row, frozen, bill.getId(), physical, false, firstPosting);
			}
			total = items.stream().map(d -> number(d.getMatNum(), "盘亏数量")).reduce(BigDecimal.ZERO, BigDecimal::add);
		}
		require(total.compareTo(delta.abs()) == 0, "盘点差异与原损益回执数量不一致，请人工核对");
	}

	private WmsInBillDetailEntity material(WmsInsideInventoryPlanDetailEntity row, WmsInBillDetailEntity selected,
	                                       double quantity) {
		var result = new WmsInBillDetailEntity();
		// 批准属性来自不可变快照；范围、单位及数量仍由原盘点决定。
		result.setStockOwnerType("OWNED");
		result.setStockOwnerCode(null);
		result.setSourceBusinessNo(selected.getSourceBusinessNo());
		result.setSupplierCode(selected.getSupplierCode());
		result.setSupplierName(selected.getSupplierName());
		result.setMatBatch(selected.getMatBatch());
		result.setContainerCode(selected.getContainerCode());
		result.setMatBarcode(selected.getMatBarcode());
		result.setMatProduceDate(selected.getMatProduceDate());
		result.setMatValidPeriod(selected.getMatValidPeriod());
		result.setMatValidEndDate(selected.getMatValidEndDate());
		result.setMatCode(row.getMatCode());
		result.setMatName(row.getMatName());
		result.setMatSpec(row.getMatSpec());
		result.setMatGroup(row.getMatGroup());
		result.setMatGroupName(row.getMatGroupName());
		result.setMatUnit(row.getMatUnit());
		result.setOperateUnit(row.getMatUnit());
		result.setOperateNum(quantity);
		result.setMatNum(quantity);
		result.setSiteCode(row.getSiteCode());
		result.setSiteName(row.getSiteName());
		result.setWarehouseCode(row.getWarehouseCode());
		result.setWarehouseName(row.getWarehouseName());
		result.setFactoryCode(factory());
		return result;
	}
}
