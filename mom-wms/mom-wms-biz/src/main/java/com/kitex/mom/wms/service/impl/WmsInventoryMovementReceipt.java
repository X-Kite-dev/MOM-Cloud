package com.kitex.mom.wms.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.wms.entity.WmsInBillDetailEntity;
import com.kitex.mom.wms.entity.WmsInoutFlowEntity;
import com.kitex.mom.wms.entity.WmsInsideInventoryPlanDetailEntity;
import com.kitex.mom.wms.entity.WmsLiveStockEntity;
import com.kitex.mom.wms.mapper.WmsInoutFlowMapper;
import com.kitex.mom.wms.mapper.WmsLiveStockMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

import static com.kitex.mom.wms.service.impl.WmsInventoryPlanAccess.*;

/**
 * 核验实际库存收发证据，不能用申请派生的单据字段代替源实物。
 */
@Component
@RequiredArgsConstructor
public class WmsInventoryMovementReceipt {
	private final WmsInoutFlowMapper flows;
	private final WmsLiveStockMapper stocks;

	public void requireMovement(WmsInsideInventoryPlanDetailEntity row, WmsInventorySelectionSnapshot.Frozen frozen,
	                            Long billId, List<WmsInBillDetailEntity> details, boolean inbound,
	                            boolean firstPosting) {
		var quantities = new HashMap<Long, BigDecimal>();
		for (var detail : details) {
			require(detail.getId() != null && detail.getId() > 0 &&
							quantities.put(detail.getId(), number(detail.getMatNum(), "损益单数量")) == null,
					"损益单明细身份重复或缺失");
		}
		var actual = flows.selectList(Wrappers.<WmsInoutFlowEntity>lambdaQuery()
				.eq(WmsInoutFlowEntity::getTenantId, TenantContextHolder.getTenantId())
				.eq(WmsInoutFlowEntity::getFactoryCode, factory()).eq(WmsInoutFlowEntity::getBillId, billId)
				.last("FOR UPDATE"));
		require(actual != null && !actual.isEmpty(), "损益缺少实际库存收发证据，未完成调账");
		var ids = new HashSet<Long>();
		var operations = new HashSet<String>();
		var totals = new HashMap<Long, BigDecimal>();
		for (var flow : actual) {
			require(flow != null && flow.getId() != null && flow.getId() > 0 && ids.add(flow.getId())
							&& flow.getOperationRequestId() != null && operations.add(flow.getOperationRequestId())
							&& Objects.equals(flow.getTenantId(), TenantContextHolder.getTenantId()) &&
							Objects.equals(flow.getFactoryCode(), factory())
							&& Objects.equals(flow.getBillId(), billId)
							&& quantities.containsKey(flow.getBillDetailId())
							&& Objects.equals(flow.getBillNo(), "IC-" + row.getId()) &&
							"other_inventory".equals(flow.getBillType())
							&& Objects.equals(flow.getOperateType(), inbound ? "in" : "out")
							&& Objects.equals(flow.getStockProofType(), inbound ? "INBOUND" : "OUTBOUND")
							&& flow.getSourceStockId() != null && flow.getSourceStockId() > 0,
					"损益实际收发证据来源或身份不一致");
			requireScope(row, flow.getWarehouseCode(), flow.getSiteCode(), flow.getMatCode());
			var receipt = new WmsInBillDetailEntity();
			BeanUtils.copyProperties(flow, receipt);
			WmsInventorySelectionSnapshot.requireReceipt(row, frozen, receipt, inbound);
			BigDecimal quantity = number(flow.getMatNum(), "实际收发数量");
			require(quantity.signum() > 0, "实际收发数量必须大于零");
			totals.merge(flow.getBillDetailId(), quantity, BigDecimal::add);
			// 仅首次入账核验刚写入的目标库存；原件重查不能依赖以后已消耗或移走的实时库存。
			if (inbound && firstPosting) {
				var stock = stocks.selectOne(Wrappers.<WmsLiveStockEntity>lambdaQuery()
						.eq(WmsLiveStockEntity::getTenantId, TenantContextHolder.getTenantId())
						.eq(WmsLiveStockEntity::getFactoryCode, factory())
						.eq(WmsLiveStockEntity::getId, flow.getSourceStockId()).last("FOR UPDATE"));
				require(stock != null && Objects.equals(stock.getId(), flow.getSourceStockId())
						&& Objects.equals(stock.getTenantId(), TenantContextHolder.getTenantId()) &&
						Objects.equals(stock.getFactoryCode(), factory()), "盘盈实际库存身份缺失或不一致");
				requireScope(row, stock.getWarehouseCode(), stock.getSiteCode(), stock.getMatCode());
				var received = new WmsInBillDetailEntity();
				BeanUtils.copyProperties(stock, received);
				WmsInventorySelectionSnapshot.requireReceipt(row, frozen, received, true);
			}
		}
		if (!inbound) {
			WmsInventoryLossSnapshot.requireFlows(frozen, actual);
		}
		require(quantities.keySet().equals(totals.keySet()) && quantities.entrySet().stream()
						.allMatch(e -> e.getValue().compareTo(totals.get(e.getKey())) == 0),
				"损益单与逐笔库存收发证据数量不守恒");
	}

	private static void requireScope(WmsInsideInventoryPlanDetailEntity row, String warehouse, String site,
	                                 String material) {
		require(Objects.equals(row.getWarehouseCode(), warehouse) && Objects.equals(row.getSiteCode(), site)
				&& Objects.equals(row.getMatCode(), material), "实际收发库存范围与原盘点不一致");
	}
}
