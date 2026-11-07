package com.kitex.mom.wms.service.special;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.wms.dto.WmsSpecialMovementDTO.*;
import com.kitex.mom.wms.entity.*;
import com.kitex.mom.wms.enums.*;
import com.kitex.mom.wms.mapper.*;
import com.kitex.mom.wms.service.special.WmsSpecialAccess.Scope;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;

import static com.kitex.mom.wms.service.special.WmsSpecialRules.*;

/**
 * 回读实际通过单据、全部明细与实物流水，拒绝Boolean成功但未完成记账的结果。
 */
@Service
@RequiredArgsConstructor
public class WmsSpecialBillProof {
	private final WmsInBillMapper inBills;
	private final WmsOutBillMapper outBills;
	private final WmsInBillDetailMapper inDetails;
	private final WmsOutBillDetailMapper outDetails;
	private final WmsInoutFlowMapper flows;
	private final ObjectMapper json = new ObjectMapper();

	public static String billType(OrderDocument order, Direction direction) {
		return direction == Direction.IN ? "other_in" :
				order.plan().kind() == Kind.OUTSOURCE ? "other_outsource" : "other_out";
	}

	public String stamp(Scope scope, OrderDocument order, Command command) {
		try {
			return json.writeValueAsString(Map.of("specialMovement",
					Map.of("orderId", order.id(), "requestId", command.requestId(), "action",
							command.action().name(),
							"operator", scope.operator(), "approvedBy", text(order.reviewedBy()))));
		} catch (Exception e) {
			throw new IllegalStateException("收发来源无法编码", e);
		}
	}

	public String detailStamp(ExecutionLine item) {
		var value = new LinkedHashMap<String, Object>();
		value.put("lineId", item.lineId());
		value.put("sourceFlowId", item.sourceFlowId());
		try {
			return json.writeValueAsString(Map.of("specialMovement", value));
		} catch (Exception e) {
			throw new IllegalStateException("收发明细来源无法编码", e);
		}
	}

	private JsonNode stamp(String value) {
		try {
			var node = json.readTree(value == null ? "" : value);
			require(node != null && node.path("specialMovement").isObject(), "实际收发来源标记缺失");
			return node.path("specialMovement");
		} catch (CheckedException e) {
			throw e;
		} catch (Exception e) {
			throw new CheckedException("实际收发来源标记无法核对");
		}
	}

	private void scope(Scope scope, Object entity) {
		require(entity != null, "实际单据或明细未找到");
		var bean = new BeanWrapperImpl(entity);
		require(Objects.equals(bean.getPropertyValue("tenantId"), scope.tenant()) &&
						Objects.equals(bean.getPropertyValue("factoryCode"), scope.factory()),
				"实际收发证据不属于当前组织");
	}

	public WmsInoutFlowEntity origin(Scope scope, Flow expected) {
		var actual = flows.selectById(id(expected.id()));
		scope(scope, actual);
		require(Objects.equals(actual.getWarehouseCode(), scope.warehouse()) &&
						Objects.equals(actual.getSourceStockId(), id(expected.stockId())) &&
						Objects.equals(actual.getBillDetailId(), id(expected.billDetailId())) &&
						actual.getMatNum() != null &&
						BigDecimal.valueOf(actual.getMatNum()).compareTo(quantity(expected.quantity())) == 0,
				"原实际流水数量或归属不一致");
		WmsSpecialStockProof.matches(expected.identity(), actual);
		return actual;
	}

	public WmsSpecialStockPosting.Posted read(Scope scope, OrderDocument order, Command command, String billId,
	                                          Direction direction, String operator) {
		Object entity =
				direction == Direction.IN ? inBills.selectById(id(billId)) : outBills.selectById(id(billId));
		scope(scope, entity);
		var bill = new BeanWrapperImpl(entity);
		require(Objects.equals(bill.getPropertyValue("warehouseCode"), scope.warehouse()) &&
						Objects.equals(bill.getPropertyValue("state"), InOutBillStateEnum.PASSED.getCode()) &&
						Objects.equals(bill.getPropertyValue("documentId"), id(order.id())) &&
						WmsSpecialExecutionContext.DOCUMENT.equals(bill.getPropertyValue("documentTable")) &&
						billType(order, direction).equals(bill.getPropertyValue("billType")),
				"实际记账单据未通过或来源不一致");
		var header = stamp((String) bill.getPropertyValue("expansion"));
		require(header.path("orderId").asText().equals(order.id()) &&
						header.path("requestId").asText().equals(command.requestId()) &&
						header.path("action").asText().equals(command.action().name()) &&
						header.path("operator").asText().equals(operator) &&
						header.path("approvedBy").asText().equals(text(order.reviewedBy())),
				"实际单据不属于本次原请求");
		String billNo = (String) bill.getPropertyValue("billNo");
		field(billNo, 100, true, "实际单号");
		var rows = flows.selectList(
				new LambdaQueryWrapper<WmsInoutFlowEntity>().eq(WmsInoutFlowEntity::getTenantId, scope.tenant())
						.eq(WmsInoutFlowEntity::getFactoryCode, scope.factory())
						.eq(WmsInoutFlowEntity::getWarehouseCode, scope.warehouse())
						.eq(WmsInoutFlowEntity::getBillId, id(billId)).eq(WmsInoutFlowEntity::getOperateType,
								direction == Direction.IN ? InOutEnum.IN.getCode() : InOutEnum.OUT.getCode())
						.orderByAsc(WmsInoutFlowEntity::getId));
		require(rows != null && !rows.isEmpty() && rows.size() <= 200, "实际收发流水缺失或超过本次明细上限");
		var totals = new HashMap<Long, BigDecimal>();
		var detailQty = new HashMap<Long, BigDecimal>();
		var byRequest = new HashMap<String, BigDecimal>();
		var result = new ArrayList<Flow>();
		var ids = new HashSet<Long>();
		for (var flow : rows) {
			scope(scope, flow);
			require(flow.getId() != null && ids.add(flow.getId()) && flow.getBillDetailId() != null &&
							Objects.equals(flow.getBillId(), id(billId)) &&
							scope.warehouse().equals(flow.getWarehouseCode()) &&
							billNo.equals(flow.getBillNo()) && billType(order, direction).equals(flow.getBillType()) &&
							Objects.equals(flow.getOperateType(),
									direction == Direction.IN ? InOutEnum.IN.getCode() : InOutEnum.OUT.getCode()) &&
							flow.getSourceStockId() != null && flow.getStockInTime() != null,
					"实际流水标识、方向、来源或时间不完整");
			Object line =
					direction == Direction.IN ? inDetails.selectById(
							flow.getBillDetailId()) : outDetails.selectById(
							flow.getBillDetailId());
			scope(scope, line);
			var detail = new BeanWrapperImpl(line);
			require(Objects.equals(detail.getPropertyValue("billId"), id(billId)),
					"实际流水未对应本次单据明细");
			var metadata = stamp((String) detail.getPropertyValue("expansion"));
			String lineId = metadata.path("lineId").asText();
			id(lineId);
			String sourceId = metadata.path("sourceFlowId").isNull() ? null : metadata.path("sourceFlowId")
					.asText();
			if (sourceId != null) {
				id(sourceId);
			}
			require(Objects.equals(sourceId,
							flow.getSourceFlowId() == null ? null : flow.getSourceFlowId().toString()),
					"实际退回流水与原来源不一致");
			var planned = allLines(order.plan()).stream().filter(l -> l.lineId().equals(lineId)).findFirst()
					.orElseThrow(() -> new CheckedException("实际物料行不属于原申请"));
			require(planned.materialCode().equals(flow.getMatCode()) &&
							planned.unit().equals(flow.getMatUnit()) &&
							Objects.equals(flow.getMatCode(), detail.getPropertyValue("matCode")) &&
							Objects.equals(flow.getMatUnit(), detail.getPropertyValue("matUnit")),
					"实际记账物料或单位不属于原计划");
			require(flow.getMatNum() != null, "实际数量缺失");
			var qty = quantity(number(BigDecimal.valueOf(flow.getMatNum())));
			totals.merge(flow.getBillDetailId(), qty, BigDecimal::add);
			var amount = (Double) detail.getPropertyValue("matNum");
			require(amount != null, "实际单据明细数量缺失");
			detailQty.put(flow.getBillDetailId(), quantity(number(BigDecimal.valueOf(amount))));
			var selections = command.lines().stream()
					.filter(i -> Objects.equals(i.lineId(), lineId) &&
							Objects.equals(i.sourceFlowId(), sourceId))
					.toList();
			require(!selections.isEmpty(), "实际流水不属于本次明细");
			if (direction == Direction.OUT) {
				require(Objects.equals(detail.getPropertyValue("sourceStockId"), flow.getSourceStockId()) &&
								selections.stream()
										.anyMatch(i -> Objects.equals(i.stockId(), flow.getSourceStockId().toString())),
						"实际扣减不是本次选定的库存");
			} else {
				require(selections.stream().allMatch(i -> i.stockId() == null), "入库请求不应指定现存库存");
			}
			var identity = WmsSpecialStockProof.identity(flow);
			byRequest.merge(
					lineId + ":" + text(sourceId) + ":" +
							(direction == Direction.OUT ? flow.getSourceStockId() : ""),
					qty, BigDecimal::add);
			result.add(new Flow(flow.getId().toString(), lineId, direction, number(qty), sourceId,
					flow.getSourceStockId().toString(), flow.getBillDetailId().toString(), identity));
		}
		require(totals.entrySet().stream()
						.allMatch(e -> e.getValue().compareTo(detailQty.get(e.getKey())) == 0),
				"实际流水与记账明细数量不守恒");
		var expected = new HashMap<String, BigDecimal>();
		for (var item : command.lines()) {
			expected.merge(item.lineId() + ":" + text(item.sourceFlowId()) + ":" +
							(direction == Direction.OUT ? item.stockId() : ""), quantity(item.quantity()),
					BigDecimal::add);
		}
		require(expected.keySet().equals(byRequest.keySet()) &&
						expected.entrySet().stream()
								.allMatch(e -> e.getValue().compareTo(byRequest.get(e.getKey())) == 0),
				"本次实际收发数量与原请求不一致");
		return new WmsSpecialStockPosting.Posted(billId, billNo, direction, List.copyOf(result));
	}
}
