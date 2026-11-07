package com.kitex.mom.wms.service.impl.externalinventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.kitex.mom.wms.dto.WmsExternalInventoryLossDTO.PostedLine;
import com.kitex.mom.wms.entity.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

import static com.kitex.mom.wms.service.impl.WmsInventoryPlanAccess.require;
import static com.kitex.mom.wms.service.impl.externalinventory.ExternalLossStore.str;

/**
 * 原计划已办复核及首次过账的真实出库、流水、原始实物和余额逐笔守恒校验。
 */
@Component
@RequiredArgsConstructor
public class ExternalLossReceipt {
	private final ExternalLossStore store;
	private final ExternalLossSelection selection;

	/**
	 * 按来源明细或IC号同时查入出两类，发现旧单据即拒绝新POST。
	 */
	void noBill(WmsInsideInventoryPlanDetailEntity row) {
		require(heads("wms_in_bill", row).isEmpty() && heads("wms_out_bill", row).isEmpty(),
				"该明细已有损益单据，不能重复过账");
	}

	private List<Map<String, Object>> heads(String table, WmsInsideInventoryPlanDetailEntity row) {
		return store.jdbc.queryForList("select * from mom_wms." + table + " where tenant_id=? and factory_code=? and " +
						"((document_id=? and document_table='wms_inventory_detail') or bill_no=?)", row.getTenantId(),
				row.getFactoryCode(), row.getId(), "IC-" + row.getId());
	}

	/**
	 * 已过账外部实例只认当次POST永久账与实际存档，不以今日剩余库存推算历史回执。
	 */
	public void posted(WmsInsideInventoryPlanDetailEntity row, WmsInventoryAdjustmentApprovalEntity a) {
		require("1".equals(row.getPlStatus()) && "POSTED".equals(a.getStatus()) && a.getPlanId().equals(row.getPlanId())
						&& a.getPlanDetailId().equals(row.getId()) && a.getWarehouseCode().equals(row.getWarehouseCode()),
				"已办理明细与外部审批范围不一致");
		store.one("""
						select e.id from mom_wms.wms_external_loss_effect e join mom_wms.wms_external_loss_request r on r.effect_id=e.id
						and r.tenant_id=e.tenant_id and r.request_id=e.request_id and r.status='APPLIED'
						where e.tenant_id=? and e.factory_code=? and e.warehouse_code=? and e.plan_id=? and e.plan_detail_id=?
						and e.domain='OA1' and e.action='POST' and e.source_id=? and e.result_status='POSTED'
						""", row.getTenantId(), row.getFactoryCode(), row.getWarehouseCode(), row.getPlanId(), row.getId(),
				a.getId());
		check(row, selection.read(a.getSelectedDetailJson()), false);
	}

	/**
	 * 逐行对照实际出库头行、OUTBOUND操作号、首次来源与全量扣减；首次写入额外检查实时余额和容器释放。
	 */
	List<PostedLine> check(WmsInsideInventoryPlanDetailEntity row, JsonNode frozen, boolean first) {
		var out = heads("wms_out_bill", row);
		require(out.size() == 1 && heads("wms_in_bill", row).isEmpty(), "实际盘亏出库头缺失或存在多余损益单");
		var h = out.get(0);
		String billNo = "IC-" + row.getId();
		require("2".equals(str(h, "state")) && "0".equals(str(h, "del_flag")) &&
				"other_inventory".equals(str(h, "bill_type"))
				&& billNo.equals(str(h, "bill_no")) && "wms_inventory_detail".equals(str(h, "document_table"))
				&& row.getId().toString().equals(str(h, "document_id")) &&
				row.getWarehouseCode().equals(str(h, "warehouse_code")), "实际盘亏出库头不符");
		var lines = children("wms_out_bill_detail", row, h);
		var flows = children("wms_inout_flow", row, h);
		var pieces = frozen.path("lossStocks");
		require(lines.size() == pieces.size() && flows.size() == pieces.size(), "实际出库行、流水与精确分配数不符");
		var result = new ArrayList<PostedLine>();
		var total = BigDecimal.ZERO;
		for (var v : pieces) {
			String sid = v.path("stockId").textValue();
			var detail = one(lines, "source_stock_id", sid);
			String lineId = str(detail, "id");
			var flow = one(flows, "bill_detail_id", lineId);
			var source = new ArrayList<JsonNode>();
			frozen.path("sources").forEach(s -> {
				if (sid.equals(s.path("stockId").textValue())) {
					source.add(s);
				}
			});
			require(source.size() == 1, "精确扣减行原始实收来源不唯一");
			var p = v.path("physical");
			var quantity = new BigDecimal(v.path("quantity").textValue());
			String operation = "OUT-STOCK:" + lineId + ":" + sid;
			require(operation.equals(str(flow, "operation_request_id")) && sid.equals(str(flow, "source_stock_id")) &&
							billNo.equals(str(flow, "bill_no"))
							&& "OUTBOUND".equals(str(flow, "stock_proof_type")) && "out".equals(str(flow, "operate_type")) &&
							"other_inventory".equals(str(flow, "bill_type"))
							&& source.get(0).path("inBillId").asText().equals(str(flow, "source_in_bill_id")) &&
							source.get(0).path("inBillDetailId").asText().equals(str(flow, "source_in_bill_detail_id")),
					"实际出库流水与原始实收行不匹配");
			for (var actual : List.of(detail, flow)) {
				require("0".equals(str(actual, "del_flag")) &&
						quantity.compareTo(new BigDecimal(str(actual, "mat_num"))) == 0, "实际出库实物数量不匹配");
				p.fieldNames().forEachRemaining(k -> {
					if (!"serialManaged".equals(k) || actual == flow) {
						String column = k.replaceAll("([A-Z])", "_$1").toLowerCase(Locale.ROOT);
						require(Objects.equals(str(actual, column), p.get(k).asText(null)),
								"实际出库实物属性不匹配：" + k);
					}
				});
			}
			if (first) {
				var remain = new BigDecimal(v.path("currentQuantity").textValue()).subtract(quantity);
				var stocks = store.jdbc.queryForList(
						"select mat_num from mom_wms.wms_live_stock where tenant_id=? and factory_code=? and id=? and del_flag='0'",
						row.getTenantId(), row.getFactoryCode(), Long.valueOf(sid));
				require(remain.signum() == 0 ? stocks.isEmpty() :
								stocks.size() == 1 && remain.compareTo(new BigDecimal(str(stocks.get(0), "mat_num"))) == 0,
						"出库后的实物余额与冻结扣减不符");
				String container = p.path("containerCode").asText(null);
				if (remain.signum() == 0 && container != null && !container.isBlank()) {
					checkContainer(row, container);
				}
			}
			result.add(new PostedLine(str(h, "id"), billNo, lineId, str(flow, "id"), sid,
					ExternalLossBasis.decimal(quantity), row.getMatUnit(), operation));
			total = total.add(quantity);
		}
		require(total.compareTo(
						BigDecimal.valueOf(row.getCurrentNumber()).subtract(BigDecimal.valueOf(row.getInventoryNum()))) == 0,
				"外部盘亏实际扣减合计不守恒");
		return result;
	}

	private void checkContainer(WmsInsideInventoryPlanDetailEntity row, String code) {
		var remaining = store.jdbc.queryForList(
				"select mat_code from mom_wms.wms_live_stock where tenant_id=? and factory_code=? and container_code=? and del_flag='0'",
				row.getTenantId(), row.getFactoryCode(), code);
		var barcode = store.one(
				"select is_used from mom_wms.wms_container_barcode where tenant_id=? and factory_code=? and code=? and del_flag='0' for share nowait",
				row.getTenantId(), row.getFactoryCode(), code);
		require((remaining.isEmpty() ? "0" : "1").equals(str(barcode, "is_used")),
				"容器占用与实际余货不符，本次过账回滚");
		if (remaining.stream().noneMatch(r -> row.getMatCode().equals(str(r, "mat_code")))) {
			require(store.jdbc.queryForList(
					"select id from mom_wms.wms_container_use_history where tenant_id=? and factory_code=? and code=? and mat_code=? and end_time is null and del_flag='0'",
					row.getTenantId(), row.getFactoryCode(), code, row.getMatCode()).isEmpty(), "容器使用历史仍未关闭");
		}
	}

	private List<Map<String, Object>> children(String table, WmsInsideInventoryPlanDetailEntity row,
	                                           Map<String, Object> h) {
		return store.jdbc.queryForList(
				"select * from mom_wms." + table + " where tenant_id=? and factory_code=? and bill_id=? order by id",
				row.getTenantId(), row.getFactoryCode(), h.get("id"));
	}

	private Map<String, Object> one(List<Map<String, Object>> rows, String k, String value) {
		var matches = rows.stream().filter(r -> value.equals(str(r, k))).toList();
		require(matches.size() == 1, "真实出库行与流水须一一对应");
		return matches.get(0);
	}
}
