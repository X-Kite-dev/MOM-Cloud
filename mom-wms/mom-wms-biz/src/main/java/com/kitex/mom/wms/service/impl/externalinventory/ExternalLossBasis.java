package com.kitex.mom.wms.service.impl.externalinventory;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.mom.wms.dto.WmsExternalInventoryLossDTO.*;
import com.kitex.mom.wms.entity.*;
import com.kitex.mom.wms.mapper.*;
import com.kitex.mom.wms.service.*;
import com.kitex.mom.wms.service.impl.*;
import com.kitex.mom.wms.util.WmsStockAvailability;
import com.kitex.mom.wms.util.WmsSerialStockGuard;
import com.kitex.common.core.exception.CheckedException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.security.MessageDigest;

import static com.kitex.mom.wms.service.impl.WmsInventoryPlanAccess.require;

/**
 * 同一仓冻结锁下，按原明细完整范围核单一分组复盘及原始实收事实。
 */
@Service
@RequiredArgsConstructor
public class ExternalLossBasis {
	private final WmsInventoryPlanAccess plans;
	private final WmsLiveStockMapper live;
	private final WmsInsideInventoryPlanDetailMapper details;
	private final WmsInventoryStockCheckService checks;
	private final WmsInventoryOwnerCountEvidence ownerCounts;
	private final WmsInventoryVariancePolicyMapper recounts;
	private final ExternalLossMandates mandates;
	private final ExternalLossProvenance provenance;
	private final ExternalLossStore store;
	private final WmsMaterialReservationService production;
	private final WmsSalesStockReservationService sales;
	private final ObjectMapper json;

	record Facts(Basis basis, WmsInsideInventoryPlanDetailEntity detail, List<WmsLiveStockEntity> stocks,
	             List<Candidate> candidates, String recountRequest, String identityReadAt) {
	}

	/**
	 * 读取整条明细范围的单组复盘、同厂货主与有效授权；写入时先锁授权和证件，再按实物主键升序取锁。异厂、缺源或实物超过100件拒绝。
	 */
	Facts inspect(DetailScope s, boolean lock, String evidenceId) {
		var row = lock ? plans.detail(Long.valueOf(s.planDetailId())) : details.selectOne(
				Wrappers.<WmsInsideInventoryPlanDetailEntity>lambdaQuery()
						.eq(WmsInsideInventoryPlanDetailEntity::getTenantId, Long.valueOf(s.tenantId()))
						.eq(WmsInsideInventoryPlanDetailEntity::getFactoryCode, s.factoryCode())
						.eq(WmsInsideInventoryPlanDetailEntity::getId, Long.valueOf(s.planDetailId())));
		require(row != null, "当前盘点明细不存在");
		var plan = plans.plan(row.getPlanId(), false);
		require(s.planId().equals(plan.getId().toString()) && s.warehouseCode().equals(plan.getWarehouseCode()) &&
						s.warehouseCode().equals(row.getWarehouseCode())
						&& "1".equals(plan.getStatus()) && "0".equals(plan.getHandleState()) && "1".equals(row.getStatus()) &&
						"0".equals(row.getPlStatus()),
				"盘点计划或明细尚未实盘完成、已关闭或已办理");
		var check = checks.inspect(row);
		require("EXTERNAL_OWNERSHIP".equals(check.status()) && check.basis().size() <= 100 && !check.basis().isEmpty()
						&& "DIFFERENCE".equals(check.ownerReview().status()) && check.ownerReview().groups().size() == 1,
				"须为一组完整外部货权复盘差额且不超过100条实物");
		var group = check.ownerReview().groups().get(0);
		require(Set.of("CUSTOMER_SUPPLIED", "CONSIGNED").contains(group.ownerType()) &&
						group.bookQuantity().equals(check.bookQuantity())
						&& group.bookQuantity().equals(check.liveQuantity()) && row.getMatUnit().equals(group.matUnit())
						&& new BigDecimal(group.difference()).signum() < 0 &&
						new BigDecimal(group.countedQuantity()).compareTo(BigDecimal.valueOf(row.getInventoryNum())) == 0,
				"仅支持同一来源、同单位的单一外部盘亏");
		// 每笔实物分配最多8位小数，整组汇总须能由这些分配精确组成。
		var lossQuantity = new BigDecimal(group.difference()).negate().stripTrailingZeros();
		require(lossQuantity.scale() <= 8, "盘亏汇总数量超过8位小数，不能精确分配");
		var rec = recounts.latestRecount(row.getTenantId(), row.getFactoryCode(), row.getPlanId(), row.getId());
		require(rec != null && rec.getId().equals(row.getLastRecountId()) &&
				rec.getId().toString().equals(check.ownerReview().recordId()), "已失效的分组复盘");
		var owner = mandates.resolve(s, group.ownerType(), group.ownerCode(), null);
		String identityReadAt = ExternalLossStore.str(store.one("select clock_timestamp()::timestamp as t"), "t");
		var mandate = mandates.active(s, owner.ownerType(), owner.id(), lock);
		require(mandate.ownerCode().equals(owner.code()), "授权货主编码不一致");
		if (lock && evidenceId != null) {
			store.one(
					"select id from mom_wms.wms_external_loss_evidence where id=? and tenant_id=? and factory_code=? and warehouse_code=? and plan_detail_id=? for share nowait",
					Long.valueOf(evidenceId), row.getTenantId(), s.factoryCode(), s.warehouseCode(), row.getId());
		}
		var stocks = live.selectList(
				Wrappers.<WmsLiveStockEntity>lambdaQuery().eq(WmsLiveStockEntity::getTenantId, row.getTenantId())
						.eq(WmsLiveStockEntity::getFactoryCode, s.factoryCode())
						.eq(WmsLiveStockEntity::getWarehouseCode, s.warehouseCode())
						.eq(WmsLiveStockEntity::getSiteCode, row.getSiteCode())
						.eq(WmsLiveStockEntity::getMatCode, row.getMatCode())
						.eq(row.getMatBatch() != null && !row.getMatBatch().isBlank(), WmsLiveStockEntity::getMatBatch,
								row.getMatBatch())
						.orderByAsc(WmsLiveStockEntity::getId).last(lock ? "FOR UPDATE NOWAIT" : ""));
		require(stocks.size() == check.basis().size(), "范围实物已变化");
		for (int j = 0; j < stocks.size(); j++) {
			var stock = stocks.get(j);
			var counted = check.basis().get(j);
			require(stock.getId().toString().equals(counted.stockId()) && stock.getMatNum() != null &&
					Double.isFinite(stock.getMatNum()) && stock.getMatNum() > 0
					&& BigDecimal.valueOf(stock.getMatNum()).compareTo(new BigDecimal(counted.quantity())) == 0 &&
					group.ownerType().equals(stock.getStockOwnerType())
					&& owner.code().equals(stock.getStockOwnerCode()) &&
					group.sourceBusinessNo().equals(stock.getSourceBusinessNo())
					&& group.matUnit().equals(stock.getMatUnit()), "复盘逐行依据与实际库存不一致");
		}
		var sources = provenance.verify(s, stocks, lock);
		var candidates = new ArrayList<Candidate>();
		var reserved = sales.activeReservedNums(new HashSet<>(stocks.stream().map(WmsLiveStockEntity::getId).toList()));
		require(reserved != null, "销售预留查询失败");
		for (var stock : stocks) {
			var prod = production.activeReservedNum(stock.getId());
			var sale = reserved.getOrDefault(stock.getId(), BigDecimal.ZERO);
			require(prod != null && prod.signum() >= 0 && sale != null && sale.signum() >= 0, "预留依据异常");
			var amount = BigDecimal.valueOf(stock.getMatNum());
			var available = amount.subtract(prod).subtract(sale);
			boolean serial = Boolean.TRUE.equals(stock.getSerialManaged());
			String reason = unavailableReason(stock, available, serial);
			String version = digest(
					Arrays.asList(stock.getId().toString(), ExternalLossProvenance.physical(stock), amount, prod, sale,
							stock.getStatus(),
							stock.getQualityResult(), stock.getQualityStatus(), stock.getIsQualityTest(),
							stock.getSourceInBillId(), stock.getSourceInBillDetailId()));
			candidates.add(new Candidate(stock.getId().toString(), version, decimal(amount),
					decimal(available.max(BigDecimal.ZERO)), stock.getMatUnit(),
					serial, reason == null, reason, stock.getMatBatch(), stock.getMatBarcode(),
					stock.getContainerCode(), stock.getSiteCode(), stock.getSupplierName()));
		}
		var b = new Basis(true, null, rec.getId().toString(), ownerCounts.basisVersion(row, check.basis()), group.key(),
				group.bookQuantity(),
				group.countedQuantity(), lossQuantity.toPlainString(), row.getMatCode(),
				row.getMatUnit(), owner, group.sourceBusinessNo(), mandate, sources);
		return new Facts(b, row, stocks, candidates, rec.getRequestId(), identityReadAt);
	}

	/**
	 * 逐件候选和审批重建都复用真实出库序列门禁，防止缺条码的遗留件先被选扣。
	 */
	private String unavailableReason(WmsLiveStockEntity stock, BigDecimal available, boolean serial) {
		if (serial) {
			try {
				WmsSerialStockGuard.requireWhole(stock, BigDecimal.ONE);
			} catch (CheckedException incompleteIdentity) {
				return "SERIAL_IDENTITY_INVALID";
			}
		}
		if (!WmsStockAvailability.isPhysicallyUsable(stock) || available.signum() <= 0
				|| (serial && available.compareTo(BigDecimal.ONE) < 0)) {
			return "STOCK_UNAVAILABLE";
		}
		return null;
	}

	private String digest(Object value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(value)));
		} catch (java.security.NoSuchAlgorithmException | java.io.IOException ex) {
			throw new IllegalStateException("库存依据摘要生成失败", ex);
		}
	}

	static String decimal(BigDecimal value) {
		return value.stripTrailingZeros().toPlainString();
	}
}
