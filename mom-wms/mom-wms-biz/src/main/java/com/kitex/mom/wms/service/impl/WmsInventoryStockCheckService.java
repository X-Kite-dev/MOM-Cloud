package com.kitex.mom.wms.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.wms.entity.WmsInsideInventoryPlanDetailEntity;
import com.kitex.mom.wms.entity.WmsLiveStockEntity;
import com.kitex.mom.wms.mapper.WmsLiveStockMapper;
import com.kitex.mom.wms.util.WmsStockOwnership;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.kitex.mom.wms.service.impl.WmsInventoryPlanAccess.*;

/**
 * 原计划范围的只读库存依据；申请通过后，实际调账仍须重新锁库存并核对。
 */
@Service
@RequiredArgsConstructor
public class WmsInventoryStockCheckService {
	private final WmsLiveStockMapper stocks;
	private final WmsInventoryOwnerCountEvidence ownerCounts;

	/**
	 * 调用方先核验原明细查看或损益权限；不开放独立HTTP入口。
	 */
	public Check inspect(WmsInsideInventoryPlanDetailEntity row) {
		String factory = factory();
		Long tenant = TenantContextHolder.getTenantId();
		require(row != null && row.getId() != null && row.getPlanId() != null
						&& Objects.equals(tenant, row.getTenantId()) && Objects.equals(factory, row.getFactoryCode())
						&& StrUtil.isAllNotBlank(row.getWarehouseCode(), row.getMatCode(), row.getSiteCode(), row.getMatUnit()),
				"盘点明细范围无效，无法核对库存");
		BigDecimal book = number(row.getCurrentNumber(), "原账面数量");
		boolean nonzero = book.compareTo(number(row.getInventoryNum(), "实盘数量")) != 0;
		var found = stocks.selectList(Wrappers.<WmsLiveStockEntity>lambdaQuery()
				.eq(WmsLiveStockEntity::getTenantId, tenant).eq(WmsLiveStockEntity::getFactoryCode, factory)
				.eq(WmsLiveStockEntity::getWarehouseCode, row.getWarehouseCode())
				.eq(WmsLiveStockEntity::getMatCode, row.getMatCode())
				.eq(WmsLiveStockEntity::getSiteCode, row.getSiteCode())
				.eq(StrUtil.isNotBlank(row.getMatBatch()), WmsLiveStockEntity::getMatBatch, row.getMatBatch())
				.orderByAsc(WmsLiveStockEntity::getId));
		require(found != null, "库存依据读取失败，请重试");
		var ids = new HashSet<Long>();
		var basis = new ArrayList<Basis>();
		boolean unitMismatch = false;
		boolean invalidOwner = false;
		boolean external = false;
		BigDecimal total = BigDecimal.ZERO;
		for (var stock : found) {
			require(stock != null && stock.getId() != null && stock.getId() > 0 && ids.add(stock.getId())
							&& Objects.equals(tenant, stock.getTenantId()) && Objects.equals(factory, stock.getFactoryCode())
							&& Objects.equals(row.getWarehouseCode(), stock.getWarehouseCode())
							&& Objects.equals(row.getMatCode(), stock.getMatCode())
							&& Objects.equals(row.getSiteCode(), stock.getSiteCode())
							&& (StrUtil.isBlank(row.getMatBatch()) || Objects.equals(row.getMatBatch(), stock.getMatBatch()))
							&& "0".equals(stock.getDelFlag()) && StrUtil.isNotBlank(stock.getMatUnit()),
					"库存依据与原盘点范围不一致，请核对原记录");
			BigDecimal quantity = number(stock.getMatNum(), "实时库存数量");
			String ownerType = StrUtil.isBlank(stock.getStockOwnerType())
					? WmsStockOwnership.OWNED : stock.getStockOwnerType().trim();
			boolean companyOwned = WmsStockOwnership.OWNED.equals(ownerType);
			boolean externalType = WmsStockOwnership.CUSTOMER_SUPPLIED.equals(ownerType)
					|| WmsStockOwnership.CONSIGNED.equals(ownerType);
			invalidOwner |= !(companyOwned || externalType)
					|| companyOwned && stock.getStockOwnerCode() != null && !stock.getStockOwnerCode().isEmpty()
					|| externalType && (StrUtil.isBlank(stock.getStockOwnerCode())
					|| StrUtil.isBlank(stock.getSourceBusinessNo()))
					|| StrUtil.length(stock.getStockOwnerCode()) > 64 ||
					StrUtil.length(stock.getSourceBusinessNo()) > 128;
			external |= externalType;
			unitMismatch |= !Objects.equals(row.getMatUnit(), stock.getMatUnit());
			total = total.add(quantity);
			basis.add(new Basis(stock.getId().toString(), ownerType, stock.getStockOwnerCode(),
					stock.getSourceBusinessNo(), stock.getMatUnit(), decimal(quantity)));
		}
		basis.sort(Comparator.comparing(item -> new java.math.BigInteger(item.stockId())));
		var balances = balances(basis);
		var ownerReview = ownerCounts.review(row, basis, balances);
		String status = unitMismatch ? "UNIT_MISMATCH" : invalidOwner ? "INVALID_OWNERSHIP"
				: total.compareTo(book) != 0 ? "STOCK_CHANGED" : "STALE".equals(
				ownerReview.status()) ? "OWNER_RECOUNT_REQUIRED"
				: nonzero && external ? "EXTERNAL_OWNERSHIP"
				: !nonzero && "DIFFERENCE".equals(ownerReview.status()) ? "OWNER_VARIANCE"
				: external && !"BALANCED".equals(ownerReview.status()) ? "OWNER_RECOUNT_REQUIRED" : "READY";
		return new Check(row.getWarehouseCode(), row.getMatCode(), row.getSiteCode(), row.getMatBatch(),
				row.getMatUnit(),
				decimal(book), unitMismatch ? null : decimal(total), status, "READY".equals(status), message(status),
				balances, List.copyOf(basis), ownerReview);
	}

	/**
	 * 零差异新完成已锁定原库存；历史自有库存保持旧协议。
	 */
	public void requireOwnerBalanced(WmsInsideInventoryPlanDetailEntity row, List<WmsLiveStockEntity> locked) {
		if (row.getLastRecountId() == null && locked.stream().allMatch(WmsStockOwnership::isCompanyOwned)) {
			return;
		}
		Check current = inspect(row);
		require(current.eligible(), current.message());
	}

	private List<Balance> balances(List<Basis> basis) {
		Map<Group, List<Basis>> groups = new HashMap<>();
		for (var item : basis) {
			groups.computeIfAbsent(
					new Group(item.ownerType(), item.ownerCode(), item.sourceBusinessNo(), item.matUnit()),
					ignored -> new ArrayList<>()).add(item);
		}
		var result = new ArrayList<Balance>();
		groups.forEach((key, items) -> result.add(new Balance(key.ownerType(), key.ownerCode(), key.sourceBusinessNo(),
				key.matUnit(), decimal(items.stream().map(item -> new BigDecimal(item.quantity()))
				.reduce(BigDecimal.ZERO, BigDecimal::add)), items.size())));
		Comparator<String> text = Comparator.nullsFirst(Comparator.naturalOrder());
		result.sort(Comparator.comparing(Balance::ownerType, text).thenComparing(Balance::ownerCode, text)
				.thenComparing(Balance::sourceBusinessNo, text).thenComparing(Balance::matUnit, text));
		return List.copyOf(result);
	}

	private String message(String status) {
		return switch (status) {
			case "UNIT_MISMATCH" -> "库存单位与原盘点单位不一致，请先核对库存记录";
			case "INVALID_OWNERSHIP" -> "库存货权或来源信息不完整，请先核对原入库记录";
			case "EXTERNAL_OWNERSHIP" -> "该范围含客供或寄售库存，请按货主核对差异，不能按自有库存调账";
			case "OWNER_RECOUNT_REQUIRED" -> "请先按货权、货主和来源分别复核实盘数量";
			case "OWNER_VARIANCE" -> "总数虽相符，各归属方仍有差异，请继续核对货权差异";
			case "STOCK_CHANGED" -> "当前库存与原账面数量不一致，请核对库存变化后重新盘点";
			default -> "库存范围核对通过，记账时仍将复核实时库存";
		};
	}

	private String decimal(BigDecimal value) {
		return value.stripTrailingZeros().toPlainString();
	}

	private record Group(String ownerType, String ownerCode, String sourceBusinessNo, String matUnit) {
	}

	public record Basis(String stockId, String ownerType, String ownerCode, String sourceBusinessNo,
	                    String matUnit, String quantity) {
	}

	public record Balance(String ownerType, String ownerCode, String sourceBusinessNo, String matUnit,
	                      String quantity, int stockRows) {
	}

	public record Check(String warehouseCode, String matCode, String siteCode, String matBatch, String matUnit,
	                    String bookQuantity, String liveQuantity, String status, boolean eligible, String message,
	                    List<Balance> balances, List<Basis> basis, WmsInventoryOwnerCountEvidence.Review ownerReview) {
	}
}
