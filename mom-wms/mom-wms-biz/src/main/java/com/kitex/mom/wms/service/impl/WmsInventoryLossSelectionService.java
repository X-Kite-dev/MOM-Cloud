package com.kitex.mom.wms.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.wms.dto.WmsInventoryLossStockRequest;
import com.kitex.mom.wms.entity.WmsInsideInventoryPlanDetailEntity;
import com.kitex.mom.wms.entity.WmsLiveStockEntity;
import com.kitex.mom.wms.mapper.WmsLiveStockMapper;
import com.kitex.mom.wms.service.WmsMaterialReservationService;
import com.kitex.mom.wms.service.WmsSalesStockReservationService;
import com.kitex.mom.wms.util.WmsStockAvailability;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

import static com.kitex.mom.wms.service.impl.WmsInventoryPlanAccess.*;

/**
 * 原范围内的指定实物。审批只冻结依据，不创建库存预留。
 */
@Service
@RequiredArgsConstructor
public class WmsInventoryLossSelectionService {
	private final WmsLiveStockMapper stocks;
	private final WmsMaterialReservationService production;
	private final WmsSalesStockReservationService sales;

	public record Candidate(String stockId, String quantity, String availableQuantity, String basisVersion,
	                        boolean serialManaged, boolean eligible, String message,
	                        WmsInventoryLossSnapshot.Physical detail) {
	}

	public record Page(List<Candidate> rows, boolean hasMore, String nextCursor) {
	}

	public Page candidates(WmsInsideInventoryPlanDetailEntity row, String keyword, String cursor) {
		var query = scope(row);
		String search = StrUtil.trim(keyword);
		require(search == null || search.length() <= 128, "实物查询条件不能超过128字");
		if (StrUtil.isNotBlank(search)) {
			query.and(q -> q.eq(WmsLiveStockEntity::getMatBarcode, search)
					.or().eq(WmsLiveStockEntity::getMatBatch, search).or()
					.eq(WmsLiveStockEntity::getContainerCode, search));
		}
		if (StrUtil.isNotBlank(cursor)) {
			query.gt(WmsLiveStockEntity::getId, WmsInventoryLossSnapshot.id(cursor));
		}
		var found = stocks.selectList(query.orderByAsc(WmsLiveStockEntity::getId).last("LIMIT 51"));
		validateScope(row, found);
		var reserved = reserved(found);
		var page = found.stream().limit(50).map(s -> candidate(row, s, reserved.get(s.getId()))).toList();
		return new Page(page, found.size() > 50, found.size() > 50 ? page.get(page.size() - 1).stockId() : null);
	}

	public String freeze(WmsInsideInventoryPlanDetailEntity row, List<WmsInventoryLossStockRequest> requests,
	                     String base) {
		var normalized = WmsInventoryLossSnapshot.normalized(requests);
		var found = stocks.selectList(scope(row).orderByAsc(WmsLiveStockEntity::getId).last("FOR UPDATE"));
		validateScope(row, found);
		require(found.stream().map(s -> number(s.getMatNum(), "实时库存数量")).reduce(BigDecimal.ZERO, BigDecimal::add)
				.compareTo(number(row.getCurrentNumber(), "账面数量")) == 0, "盘点后库存已变化，请重新核对实盘");
		// 原范围中任何外部货权或单位异常均不能借指定模式绕过原准入。
		for (var s : found) {
			require(owned(s) && Objects.equals(row.getMatUnit(), s.getMatUnit()),
					"原范围货权或单位不一致，不能按自有库存调账");
		}
		var values = selected(row, normalized, found);
		String frozen = WmsInventoryLossSnapshot.freeze(base, values);
		WmsInventorySelectionSnapshot.read(row, frozen, true);
		return frozen;
	}

	public void requireCurrent(WmsInsideInventoryPlanDetailEntity row, WmsInventorySelectionSnapshot.Frozen frozen,
	                           List<WmsLiveStockEntity> lockedScope) {
		if (frozen.lossStocks().isEmpty()) {
			return;
		}
		validateScope(row, lockedScope);
		var current = selected(row, frozen.lossStocks().stream().map(WmsInventoryLossSnapshot.Stock::request).toList(),
				lockedScope);
		require(current.equals(frozen.lossStocks()), "所选实物依据已变化，请撤销批准后重新申请");
	}

	private List<WmsInventoryLossSnapshot.Stock> selected(WmsInsideInventoryPlanDetailEntity row,
	                                                      List<WmsInventoryLossStockRequest> requests,
	                                                      List<WmsLiveStockEntity> found) {
		var indexed = new HashMap<Long, WmsLiveStockEntity>();
		found.forEach(s -> indexed.put(s.getId(), s));
		var reserved = reserved(found);
		var result = new ArrayList<WmsInventoryLossSnapshot.Stock>();
		for (var request : WmsInventoryLossSnapshot.normalized(requests)) {
			var stock = indexed.get(WmsInventoryLossSnapshot.id(request.stockId()));
			require(stock != null, "所选实物已移出原范围，请重新盘点");
			var candidate = candidate(row, stock, reserved.get(stock.getId()));
			require(candidate.eligible(), candidate.message());
			require(Objects.equals(candidate.basisVersion(), request.basisVersion()),
					"所选实物或预留已变化，请重新选择并申请");
			var quantity = WmsInventoryLossSnapshot.quantity(request.quantity());
			require(quantity.compareTo(new BigDecimal(candidate.availableQuantity())) <= 0,
					"分配量超过实物当前可用数量");
			// 精确出库仍以Double落账；输入量和扣减后余额都必须无损，防止流水已记但库存少扣。
			var balance = number(stock.getMatNum(), "实物库存数量").subtract(quantity);
			require(Double.isFinite(balance.doubleValue()) &&
							BigDecimal.valueOf(balance.doubleValue()).compareTo(balance) == 0,
					"扣减后余额超出当前计量精度，请细分盘点范围后重新核对");
			require(!candidate.serialManaged() || quantity.compareTo(BigDecimal.ONE) == 0, "单件实物必须整件盘亏");
			result.add(new WmsInventoryLossSnapshot.Stock(request.stockId(), WmsInventoryLossSnapshot.text(quantity),
					candidate.basisVersion(), candidate.serialManaged(), candidate.detail()));
		}
		return List.copyOf(result);
	}

	private Candidate candidate(WmsInsideInventoryPlanDetailEntity row, WmsLiveStockEntity stock,
	                            Reservations reserved) {
		var quantity = number(stock.getMatNum(), "实物库存数量");
		var available = quantity.subtract(reserved.production()).subtract(reserved.sales()).max(BigDecimal.ZERO);
		var physical = WmsInventoryLossSnapshot.physical(stock);
		String error = null;
		if (!Objects.equals(row.getMatUnit(), stock.getMatUnit())) {
			error = "库存单位不一致";
		} else if (!owned(stock)) {
			error = "货权或归属方信息需核对";
		} else if (!WmsStockAvailability.isPhysicallyUsable(stock)) {
			error = "库存锁定、效期或质量状态不允许出库";
		} else if (available.signum() <= 0) {
			error = "无可扣减量，库存已被预留";
		} else if (Boolean.TRUE.equals(stock.getSerialManaged()) &&
				(quantity.compareTo(BigDecimal.ONE) != 0 || available.compareTo(BigDecimal.ONE) < 0)) {
			error = "单件库存数量异常";
		}
		try {
			WmsInventoryLossSnapshot.parsePhysical(
					WmsInventorySelectionSnapshot.parse(WmsInventorySelectionSnapshot.write(physical)),
					row.getMatUnit());
		} catch (CheckedException ex) {
			error = "实物属性不完整，请核对原库存";
			physical = null;
		}
		return new Candidate(stock.getId().toString(), WmsInventoryLossSnapshot.text(quantity),
				WmsInventoryLossSnapshot.text(error == null ? available : BigDecimal.ZERO), basis(stock, reserved),
				Boolean.TRUE.equals(stock.getSerialManaged()), error == null, error, physical);
	}

	private boolean owned(WmsLiveStockEntity stock) {
		return (StrUtil.isBlank(stock.getStockOwnerType()) || "OWNED".equals(stock.getStockOwnerType()))
				&& (stock.getStockOwnerCode() == null || stock.getStockOwnerCode().isEmpty());
	}

	private LambdaQueryWrapper<WmsLiveStockEntity> scope(WmsInsideInventoryPlanDetailEntity row) {
		require(row != null && row.getId() != null && row.getPlanId() != null &&
						Objects.equals(row.getTenantId(), TenantContextHolder.getTenantId())
						&& Objects.equals(row.getFactoryCode(), factory()) &&
						StrUtil.isAllNotBlank(row.getWarehouseCode(), row.getSiteCode(),
								row.getMatCode(), row.getMatUnit())
						&& number(row.getCurrentNumber(), "账面数量").compareTo(number(row.getInventoryNum(), "实盘数量")) > 0,
				"仅能选择原盘亏范围的实物");
		return Wrappers.<WmsLiveStockEntity>lambdaQuery()
				.eq(WmsLiveStockEntity::getTenantId, TenantContextHolder.getTenantId())
				.eq(WmsLiveStockEntity::getFactoryCode, factory())
				.eq(WmsLiveStockEntity::getWarehouseCode, row.getWarehouseCode())
				.eq(WmsLiveStockEntity::getSiteCode, row.getSiteCode())
				.eq(WmsLiveStockEntity::getMatCode, row.getMatCode())
				.eq(StrUtil.isNotBlank(row.getMatBatch()), WmsLiveStockEntity::getMatBatch, row.getMatBatch());
	}

	private void validateScope(WmsInsideInventoryPlanDetailEntity row, List<WmsLiveStockEntity> found) {
		scope(row);
		require(found != null, "实物库存读取失败");
		var ids = new HashSet<Long>();
		for (var s : found) {
			require(s != null && s.getId() != null && s.getId() > 0 && ids.add(s.getId()) && "0".equals(s.getDelFlag())
							&& Objects.equals(s.getTenantId(), row.getTenantId()) &&
							Objects.equals(s.getFactoryCode(), row.getFactoryCode())
							&& Objects.equals(s.getWarehouseCode(), row.getWarehouseCode()) &&
							Objects.equals(s.getSiteCode(), row.getSiteCode())
							&& Objects.equals(s.getMatCode(), row.getMatCode()) &&
							(StrUtil.isBlank(row.getMatBatch()) || Objects.equals(s.getMatBatch(), row.getMatBatch())),
					"实物库存与原盘点范围不一致");
		}
	}

	private record Reservations(BigDecimal production, BigDecimal sales) {
	}

	private Map<Long, Reservations> reserved(List<WmsLiveStockEntity> found) {
		if (found.isEmpty()) {
			return Map.of();
		}
		var ids = new HashSet<Long>();
		found.forEach(s -> ids.add(s.getId()));
		var sale = sales.activeReservedNums(ids);
		require(sale != null, "销售预留读取失败，请重试");
		var result = new HashMap<Long, Reservations>();
		for (var s : found) {
			var p = production.activeReservedNum(s.getId());
			var q = sale.getOrDefault(s.getId(), BigDecimal.ZERO);
			require(p != null && q != null && p.signum() >= 0 && q.signum() >= 0, "实物预留依据无效，请核对");
			result.put(s.getId(), new Reservations(p, q));
		}
		return result;
	}

	private String basis(WmsLiveStockEntity s, Reservations r) {
		var facts = new LinkedHashMap<String, Object>();
		facts.put("tenant", s.getTenantId().toString());
		facts.put("factory", s.getFactoryCode());
		facts.put("warehouse", s.getWarehouseCode());
		facts.put("site", s.getSiteCode());
		facts.put("material", s.getMatCode());
		facts.put("id", s.getId().toString());
		facts.put("quantity", WmsInventoryLossSnapshot.text(number(s.getMatNum(), "实物数量")));
		facts.put("productionReserved", WmsInventoryLossSnapshot.text(r.production()));
		facts.put("salesReserved", WmsInventoryLossSnapshot.text(r.sales()));
		facts.put("physical", WmsInventoryLossSnapshot.physical(s));
		facts.put("serial", Boolean.TRUE.equals(s.getSerialManaged()));
		facts.put("status", s.getStatus());
		facts.put("qualityStatus", s.getQualityStatus());
		facts.put("qualityResult", s.getQualityResult());
		facts.put("qualityRequired", s.getIsQualityTest());
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(WmsInventorySelectionSnapshot.write(facts).getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}
}
