package com.kitex.mom.wms.service.impl.externalinventory;

import com.kitex.mom.wms.dto.WmsExternalInventoryLossDTO.*;
import com.kitex.mom.wms.entity.WmsLiveStockEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

import static com.kitex.mom.wms.service.impl.WmsInventoryPlanAccess.require;
import static com.kitex.mom.wms.service.impl.externalinventory.ExternalLossStore.str;

/**
 * 从当前全部原位实物逐一反查真实实收头、行及原始流水，缺任一依据整行拒绝。
 */
@Component
@RequiredArgsConstructor
public class ExternalLossProvenance {
	private final ExternalLossStore store;

	/**
	 * 原申请冻结的物流与货权属性，按原始来源逐项匹配，保留可空属性。
	 */
	record Physical(
			/** 原实物实际仓库编码，限当前租户和工厂。 */
			String warehouseCode,
			/** 原实物实际库位编码。 */
			String siteCode,
			/** 盘点范围内的物料编码。 */
			String matCode,
			/** 实物批次，可空。 */
			String matBatch,
			/** 实物库存标准计量单位。 */
			String matUnit,
			/** 原实物条码或单件序列号，非序列实物可空。 */
			String matBarcode,
			/** 原实物的单件序列管理标记，true须整件扣减。 */
			Boolean serialManaged,
			/** 收货实物的供应商编码，可空。 */
			String supplierCode,
			/** 原收货记录保留的供应商名称，可空。 */
			String supplierName,
			/** 实物所在容器编码，可空。 */
			String containerCode,
			/** 实物生产日期，ISO日期文本，可空。 */
			String matProduceDate,
			/** 物料有效期天数，可空。 */
			Integer matValidPeriod,
			/** 实物有效截止日期，ISO日期文本，可空。 */
			String matValidEndDate,
			/** 当前组的客供或寄售货权类型。 */
			String stockOwnerType,
			/** 该外部货主在实际工厂的业务编码。 */
			String stockOwnerCode,
			/** 首次实收记录保留的商业来源单号。 */
			String sourceBusinessNo) {
	}

	/**
	 * 从实际读取的库存行构造比较与冻结所用物性快照，日期沿原行值转ISO文本。
	 */
	static Physical physical(WmsLiveStockEntity s) {
		return new Physical(s.getWarehouseCode(), s.getSiteCode(), s.getMatCode(), s.getMatBatch(), s.getMatUnit(),
				s.getMatBarcode(),
				s.getSerialManaged(), s.getSupplierCode(), s.getSupplierName(), s.getContainerCode(),
				Objects.toString(s.getMatProduceDate(), null),
				s.getMatValidPeriod(), Objects.toString(s.getMatValidEndDate(), null), s.getStockOwnerType(),
				s.getStockOwnerCode(), s.getSourceBusinessNo());
	}

	/**
	 * 按表及主键升序读真实实收，核全部实物逐行身份与数量，返回与实物相同顺序的不可替换原始来源。
	 */
	List<Source> verify(DetailScope scope, List<WmsLiveStockEntity> stocks, boolean lock) {
		// 表顺序固定，表内按数值主键排序；原单作者可能先取行锁，因此不等待形成反向死锁。
		var heads = new TreeSet<Long>();
		var lines = new TreeSet<Long>();
		for (var s : stocks) {
			require(s.getSourceInBillId() != null && s.getSourceInBillDetailId() != null &&
							lines.add(s.getSourceInBillDetailId()),
					"范围存在缺失或重复的实收入库行");
			heads.add(s.getSourceInBillId());
		}
		var stockIds = stocks.stream().map(WmsLiveStockEntity::getId).toList();
		var params = new ArrayList<Object>();
		params.add(Long.valueOf(scope.tenantId()));
		params.add(scope.factoryCode());
		params.addAll(stockIds);
		params.addAll(stockIds);
		String marks = String.join(",", Collections.nCopies(stockIds.size(), "?"));
		require(store.jdbc.queryForList(
				"select id from mom_wms.wms_material_move_site where tenant_id=? and factory_code=? and " +
						"(source_stock_id in (" + marks + ") or target_stock_id in (" + marks + ")) limit 1",
				params.toArray()).isEmpty(), "范围实物存在移位履历，不能证明是原位实收");
		var h = read(scope, "wms_in_bill", "id", heads, lock);
		var d = read(scope, "wms_in_bill_detail", "id", lines, lock);
		var f = read(scope, "wms_inout_flow", "bill_detail_id", lines, lock).stream()
				.filter(r -> "in".equals(str(r, "operate_type")) && "INBOUND".equals(str(r, "stock_proof_type")))
				.toList();
		var result = new ArrayList<Source>();
		for (var s : stocks) {
			var header = unique(h, "id", s.getSourceInBillId());
			var line = unique(d, "id", s.getSourceInBillDetailId());
			var flow = unique(f, "bill_detail_id", s.getSourceInBillDetailId());
			String receipt = str(header, "bill_no");
			String operation = "IN-STOCK:" + s.getSourceInBillDetailId();
			require(receipt != null && !receipt.isBlank() && Set.of("2", "12").contains(str(header, "state")) &&
					"other_in".equals(str(header, "bill_type")) && "other_in".equals(str(flow, "bill_type")) &&
					receipt.equals(str(flow, "bill_no")) &&
					s.getWarehouseCode().equals(str(header, "warehouse_code")) &&
					s.getSourceInBillId().toString().equals(str(line, "bill_id")) &&
					s.getSourceInBillId().toString().equals(str(flow, "bill_id")) &&
					s.getSourceInBillId().toString().equals(str(flow, "source_in_bill_id")) &&
					s.getSourceInBillDetailId().toString().equals(str(flow, "source_in_bill_detail_id")) &&
					s.getId().toString().equals(str(flow, "source_stock_id")) &&
					operation.equals(str(flow, "operation_request_id")) &&
					Objects.equals(s.getSerialManaged(), flow.get("serial_managed")), "库存与原始其他实收入库身份不符");
			var p = physical(s);
			for (var r : List.of(line, flow)) {
				require(Objects.equals(p.warehouseCode(), str(r, "warehouse_code")) &&
								Objects.equals(p.siteCode(), str(r, "site_code"))
								&& Objects.equals(p.matCode(), str(r, "mat_code")) &&
								Objects.equals(p.matUnit(), str(r, "mat_unit"))
								&& Objects.equals(p.matBatch(), str(r, "mat_batch")) &&
								Objects.equals(p.matBarcode(), str(r, "mat_barcode"))
								&& Objects.equals(p.stockOwnerType(), str(r, "stock_owner_type")) &&
								Objects.equals(p.stockOwnerCode(), str(r, "stock_owner_code"))
								&& Objects.equals(p.sourceBusinessNo(), str(r, "source_business_no")) &&
								Objects.equals(p.containerCode(), str(r, "container_code"))
								&& Objects.equals(p.supplierCode(), str(r, "supplier_code")) &&
								Objects.equals(p.supplierName(), str(r, "supplier_name"))
								&& Objects.equals(p.matProduceDate(), str(r, "mat_produce_date")) &&
								Objects.equals(p.matValidEndDate(), str(r, "mat_valid_end_date"))
								&& Objects.equals(Objects.toString(p.matValidPeriod(), null), str(r, "mat_valid_period")),
						"实物属性已偏离原实收行或流水");
			}
			var original = new BigDecimal(str(line, "mat_num"));
			var current = BigDecimal.valueOf(s.getMatNum());
			require(original.compareTo(new BigDecimal(str(flow, "mat_num"))) == 0 && current.signum() > 0 &&
							current.compareTo(original) <= 0,
					"原实收量、流水量或当前余量不一致");
			result.add(new Source(s.getId().toString(), s.getSourceInBillId().toString(), receipt,
					s.getSourceInBillDetailId().toString(),
					str(flow, "id"), operation, original.stripTrailingZeros().toPlainString(),
					current.stripTrailingZeros().toPlainString(), s.getMatUnit()));
		}
		return result;
	}

	private Map<String, Object> unique(List<Map<String, Object>> rows, String key, Long id) {
		var matches = rows.stream().filter(r -> id.toString().equals(str(r, key))).toList();
		require(matches.size() == 1 && "0".equals(str(matches.get(0), "del_flag")),
				"实际原始头行或入库流水缺失或不唯一");
		return matches.get(0);
	}

	private List<Map<String, Object>> read(DetailScope s, String table, String column, Set<Long> ids, boolean lock) {
		var args = new ArrayList<Object>();
		args.add(Long.valueOf(s.tenantId()));
		args.add(s.factoryCode());
		args.addAll(ids);
		String marks = String.join(",", Collections.nCopies(ids.size(), "?"));
		return store.jdbc.queryForList(
				"select * from mom_wms." + table + " where tenant_id=? and factory_code=? and " + column + " in (" +
						marks +
						") order by id" + (lock ? " for share nowait" : ""), args.toArray());
	}
}
