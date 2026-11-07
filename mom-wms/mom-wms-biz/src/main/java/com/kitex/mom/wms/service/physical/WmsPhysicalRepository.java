package com.kitex.mom.wms.service.physical;

import com.kitex.mom.wms.dto.WmsQualityPhysicalDTO.*;
import com.kitex.mom.wms.entity.WmsQualityStatusOperationEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.*;

import static com.kitex.mom.wms.service.physical.WmsPhysicalRules.*;

/**
 * 实物账及原仓关系查询；显式组织、JSONB cast、数据库业务时间，终态只INSERT。
 */
@Repository
@RequiredArgsConstructor
public class WmsPhysicalRepository {
	private final JdbcTemplate db;
	private final WmsPhysicalJson json;

	/**
	 * 核本版数据库对象及ALWAYS保护；缺章、漂移和访问故障均拒绝。
	 */
	public void ready() {
		try {
			require(Boolean.TRUE.equals(
							db.queryForObject("SELECT mom_wms.wms_quality_physical_ready_v1()", Boolean.class)),
					"质量实物数据库保护未就绪");
		} catch (org.springframework.dao.DataAccessException e) {
			throw new com.kitex.common.core.exception.CheckedException("质量实物数据库章节缺失或保护漂移");
		}
	}

	/**
	 * 原仓锁在parent行锁前，与395使用同一锁键且仓码排序。
	 */
	public void warehouses(Long tenant, String factory, Collection<String> warehouses) {
		for (String w : new TreeSet<>(warehouses)) {
			db.queryForObject("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(?,0))", Integer.class,
					"WMS_SPECIAL_WAREHOUSE:" + tenant + ":" + factory + ":" + w);
		}
	}

	/**
	 * 按原组织、operation及原号读取永久终态；无行才返回null，正文损坏抛错。
	 */
	public Receipt find(Command c) {
		return find(c.source().key().tenantId(), c.source().key().factoryCode(), c.operationId(),
				c.requestId());
	}

	/**
	 * 读取原operation唯一永久APPLIED回执，不借当前库存推测生效。
	 */
	public Receipt applied(WmsQualityStatusOperationEntity op) {
		List<String> ids = db.queryForList(
				"SELECT request_id FROM mom_wms.wms_quality_physical_request WHERE tenant_id=? AND factory_code=? AND operation_id=? AND outcome='APPLIED'",
				String.class, op.getTenantId(), op.getFactoryCode(), op.getId());
		require(ids.size() <= 1, "原操作存在重复生效账");
		return ids.isEmpty() ? null : find(op.getTenantId().toString(), op.getFactoryCode(),
				op.getId().toString(), ids.get(0));
	}

	private Receipt find(String tenant, String factory, String operation, String request) {
		List<Receipt> rows = db.query(
				"SELECT * FROM mom_wms.wms_quality_physical_request WHERE tenant_id=? AND factory_code=? AND operation_id=? AND request_id=?",
				(r, n) -> {
					Command c = json.decode(r.getString("command_json"), Command.class);
					command(c);
					require(c.source().key().tenantId().equals(r.getString("tenant_id")) &&
							c.source().key().factoryCode().equals(r.getString("factory_code")) &&
							c.operationId().equals(r.getString("operation_id")) &&
							c.requestId().equals(r.getString("request_id")) &&
							c.actorId().equals(r.getString("actor_id")), "永久账列与完整原文不一致");
					String outcome = r.getString("outcome");
					require(Set.of("APPLIED", "CANCELLED").contains(outcome), "永久账终态无效");
					return new Receipt(1, outcome, c, r.getString("actor_id"), r.getString("ended_by"),
							r.getString("ended_by_name"),
							time(r.getObject("ended_at", LocalDateTime.class)));
				}
				, id(tenant), factory, id(operation), request);
		require(rows.size() <= 1, "原号重复终态");
		return rows.isEmpty() ? null : rows.get(0);
	}

	/**
	 * 先保存APPLIED原件，库存仍真实存在；后续归档与parent更新由同一事务和延迟守卫裁决。
	 */
	public Receipt insert(Command c, String outcome, String username, String name) {
		field(username, 100);
		field(name, 100);
		require(db.update(
						"INSERT INTO mom_wms.wms_quality_physical_request (tenant_id,factory_code,operation_id,request_id,actor_id,outcome,command_json,ended_by,ended_by_name,ended_at) VALUES (?,?,?,?,?,?,CAST(? AS jsonb),?,?,clock_timestamp()::timestamp(6))",
						id(c.source().key().tenantId()), c.source().key().factoryCode(), id(c.operationId()),
						c.requestId(), id(c.actorId()), outcome, json.encode(c), username, name) == 1,
				"质量实物原号落账失败");
		return Objects.requireNonNull(find(c));
	}

	/**
	 * 只归档原ID及原组织，保留数量且清当前owner；每件行数必须精确为一。
	 */
	public void archive(Command c) {
		for (Piece p : c.source().pieces()) {
			require(db.update(
							"UPDATE mom_wms.wms_live_stock SET del_flag='1',quality_physical_operation_id=NULL WHERE id=? AND tenant_id=? AND factory_code=? AND del_flag='0' AND status=1 AND quality_status=? AND (quality_physical_operation_id=? OR quality_physical_operation_id IS NULL)",
							id(p.stockId()), id(c.source().key().tenantId()), c.source().key().factoryCode(),
							c.source().key().action() + "_PENDING", id(c.operationId())) == 1,
					"原实物归档行数不一致，已回滚");
		}
	}

	/**
	 * 定向REHOLD同一行原子恢复HOLD并清该parent当前owner，不留下裸清所有权中间态。
	 */
	public void clearOwner(WmsQualityStatusOperationEntity parent, Long stockId) {
		require(db.update(
						"UPDATE mom_wms.wms_live_stock SET quality_physical_operation_id=NULL,quality_status='HOLD',quality_result='UN_QUALIFIED' WHERE id=? AND tenant_id=? AND factory_code=? AND del_flag='0' AND (quality_physical_operation_id=? OR quality_physical_operation_id IS NULL)",
						stockId, parent.getTenantId(), parent.getFactoryCode(), parent.getId()) == 1,
				"原处置所有权清理失败");
	}

	/**
	 * 首次PENDING到ACTION_PENDING与完整Source同条更新；owner已由本事务库存写入，不允许后补来源。
	 */
	public void completeClassification(WmsQualityStatusOperationEntity op, Source source) {
		require(db.update(
						"UPDATE mom_wms.wms_quality_status_operation SET status='ACTION_PENDING',affected_quantity=?,affected_stock_ids=?,split_recorded=?,split_remainder_stock_ids=?,split_lineage_json=?,split_recorded_at=?,result_message=?,physical_source_json=CAST(? AS jsonb),update_time=clock_timestamp()::timestamp(6) WHERE id=? AND tenant_id=? AND factory_code=? AND status='PENDING' AND physical_source_json IS NULL",
						op.getAffectedQuantity(), op.getAffectedStockIds(), op.getSplitRecorded(),
						op.getSplitRemainderStockIds(), op.getSplitLineageJson(),
						java.sql.Timestamp.valueOf(op.getSplitRecordedAt()), op.getResultMessage(), json.encode(source),
						op.getId(), op.getTenantId(), op.getFactoryCode()) == 1,
				"质量实物来源冻结失败");
		String stored = db.queryForObject(
				"SELECT physical_source_json::text FROM mom_wms.wms_quality_status_operation WHERE id=? AND tenant_id=? AND factory_code=?",
				String.class, op.getId(), op.getTenantId(), op.getFactoryCode());
		require(source.equals(json.decode(stored, Source.class)), "质量实物冻结Source回读不一致");
		op.setPhysicalSourceJson(stored);
	}

	/**
	 * 纯读普通定向成功关联；旧status保持原值。
	 */
	public String superseded(WmsQualityStatusOperationEntity op) {
		List<String> rows = db.queryForList(
				"SELECT id::text FROM mom_wms.wms_quality_status_operation WHERE tenant_id=? AND factory_code=? AND rehold_of_physical_operation_id=? AND action='HOLD' AND status='SUCCESS' AND del_flag='0'",
				String.class, op.getTenantId(), op.getFactoryCode(), op.getId());
		require(rows.size() <= 1, "普通原处置存在重复成功重隔离");
		return rows.isEmpty() ? null : rows.get(0);
	}

	/**
	 * 原仓候选只来自同组织永久原件和真实库存，不从今日物料主数据猜仓。
	 */
	public List<String> candidates(Long tenant, String factory) {
		var rows = db.queryForList(
				"SELECT DISTINCT w FROM (SELECT p->>'warehouseCode' w FROM mom_wms.wms_quality_physical_request r CROSS JOIN LATERAL jsonb_array_elements(r.command_json->'source'->'pieces') p WHERE r.tenant_id=? AND r.factory_code=? AND r.outcome='APPLIED' UNION ALL SELECT warehouse_code FROM mom_wms.wms_live_stock WHERE tenant_id=? AND factory_code=? UNION ALL SELECT warehouse_code FROM mom_wms.wms_quality_status_operation WHERE tenant_id=? AND factory_code=? UNION ALL SELECT p->>'warehouseCode' FROM mom_wms.wms_quality_status_operation o CROSS JOIN LATERAL jsonb_array_elements(COALESCE(o.physical_source_json->'pieces','[]'::jsonb)) p WHERE o.tenant_id=? AND o.factory_code=?) s WHERE w IS NOT NULL AND w<>'' ORDER BY w LIMIT 100001",
				String.class, tenant, factory, tenant, factory, tenant, factory, tenant, factory);
		require(rows.size() <= 100000, "原仓全集超过安全读取上限，不能截断授权");
		return rows;
	}

	/**
	 * 原仓集合及完整性与列表SQL使用相同关系，精确GET不走首仓捷径。
	 */
	public List<String> originalWarehouses(Long tenant, String factory, Long operation) {
		return db.queryForList(RELATION +
						" SELECT DISTINCT e.w FROM evidence e WHERE e.id=? AND NOT EXISTS (SELECT 1 FROM evidence bad WHERE bad.id=e.id AND (NOT COALESCE(bad.complete,false) OR bad.w IS NULL OR bad.w='')) ORDER BY e.w",
				String.class, tenant, factory, operation);
	}

	/**
	 * 授权条件先于count/order/limit；不允许含未授权原仓的整单进入分页。
	 */
	public List<Long> page(Long tenant, String factory, List<String> proved, WmsQualityStatusOperationEntity q,
	                       String barcode, long offset, long limit, boolean count, long[] total) {
		List<Object> args = new ArrayList<>(List.of(tenant, factory, json.encode(proved)));
		String where = " WHERE o.id IN (SELECT id FROM evidence GROUP BY id HAVING bool_and(complete) AND count(w)>0 AND bool_and(COALESCE(jsonb_exists(CAST(? AS jsonb),w),false)))";
		if (q.getId() != null) {
			where += " AND o.id=?";
			args.add(q.getId());
		}
		for (String[] f : List.of(new String[]{
						"hold_no", q.getHoldNo()}
				, new String[]{
						"mat_code", q.getMatCode()}
				, new String[]{
						"source_type", q.getSourceType()}
				, new String[]{
						"action", q.getAction()}
				, new String[]{
						"status", q.getStatus()}
		)) {
			if (f[1] != null && !f[1].isBlank()) {
				where += " AND o." + f[0] + "=?";
				args.add(f[1]);
			}
		}
		if (q.getWarehouseCode() != null && !q.getWarehouseCode().isBlank()) {
			where += " AND EXISTS(SELECT 1 FROM evidence e WHERE e.id=o.id AND e.w=?)";
			args.add(q.getWarehouseCode());
		}
		if (q.getAffectedStockIds() != null && !q.getAffectedStockIds().isBlank()) {
			id(q.getAffectedStockIds());
			where += " AND ?=ANY(string_to_array(o.affected_stock_ids,','))";
			args.add(q.getAffectedStockIds());
		}
		if (barcode != null && !barcode.isBlank()) {
			where += " AND (EXISTS(SELECT 1 FROM jsonb_array_elements(COALESCE(o.frozen_source->'pieces','[]'::jsonb)) p WHERE p->'identity'->>'matBarcode'=?) OR (o.frozen_source IS NULL AND EXISTS(SELECT 1 FROM mom_wms.wms_live_stock s WHERE s.tenant_id=o.tenant_id AND s.factory_code=o.factory_code AND s.id::text=ANY(string_to_array(o.affected_stock_ids,',')) AND s.mat_barcode=?)))";
			args.add(barcode);
			args.add(barcode);
		}
		total[0] = Objects.requireNonNull(
				db.queryForObject(RELATION + " SELECT count(*) FROM scoped o" + where, Long.class,
						args.toArray()));
		args.add(limit);
		args.add(offset);
		return db.queryForList(RELATION + " SELECT o.id FROM scoped o" + where +
				" ORDER BY o.create_time DESC,o.id DESC LIMIT ? OFFSET ?", Long.class, args.toArray());
	}

	// 一个操作有多个原仓；exact IDs须全部同scope命中且规范唯一，PENDING候选不截断原FIFO。
	private static final String RELATION = """
			WITH scoped AS (SELECT o.*,COALESCE(o.physical_source_json,(SELECT r.command_json->'source' FROM mom_wms.wms_quality_physical_request r WHERE r.tenant_id=o.tenant_id AND r.factory_code=o.factory_code AND r.operation_id=o.id AND r.outcome='APPLIED')) frozen_source FROM mom_wms.wms_quality_status_operation o WHERE o.tenant_id=? AND o.factory_code=? AND o.del_flag='0'),
			evidence AS (
			 SELECT o.id, p->>'warehouseCode' w, true complete FROM scoped o CROSS JOIN LATERAL jsonb_array_elements(o.frozen_source->'pieces') p WHERE o.frozen_source IS NOT NULL
			 UNION ALL SELECT o.id,o.special_return_binding_json::jsonb->'target'->>'warehouseCode',true FROM scoped o WHERE o.frozen_source IS NULL AND o.special_return_binding_json IS NOT NULL
			 UNION ALL SELECT o.id,s.warehouse_code,
			   (SELECT count(*)=count(DISTINCT x) AND bool_and(x ~ '^[1-9][0-9]{0,18}$') FROM unnest(string_to_array(o.affected_stock_ids,',')) x)
			   AND (SELECT count(*) FROM mom_wms.wms_live_stock z WHERE z.tenant_id=o.tenant_id AND z.factory_code=o.factory_code AND z.id::text=ANY(string_to_array(o.affected_stock_ids,',')))=cardinality(string_to_array(o.affected_stock_ids,','))
			   AND (o.warehouse_code IS NULL OR o.warehouse_code='' OR o.warehouse_code=s.warehouse_code)
			 FROM scoped o LEFT JOIN mom_wms.wms_live_stock s ON s.tenant_id=o.tenant_id AND s.factory_code=o.factory_code AND s.id::text=ANY(string_to_array(o.affected_stock_ids,','))
			 WHERE o.frozen_source IS NULL AND o.special_return_binding_json IS NULL AND COALESCE(o.affected_stock_ids,'')<>''
			 UNION ALL SELECT o.id,o.warehouse_code,true FROM scoped o WHERE o.frozen_source IS NULL AND o.special_return_binding_json IS NULL AND COALESCE(o.affected_stock_ids,'')='' AND COALESCE(o.warehouse_code,'')<>''
			 UNION ALL SELECT o.id,s.warehouse_code,true FROM scoped o JOIN mom_wms.wms_live_stock s ON s.tenant_id=o.tenant_id AND s.factory_code=o.factory_code AND s.del_flag='0' AND s.mat_num>0 AND s.mat_code=o.mat_code
			 AND (COALESCE(o.mat_batch,'')='' OR s.mat_batch=o.mat_batch) AND (COALESCE(o.mat_unit,'')='' OR s.mat_unit=o.mat_unit) AND (COALESCE(o.supplier_code,'')='' OR s.supplier_code=o.supplier_code) AND (COALESCE(o.site_code,'')='' OR s.site_code=o.site_code) AND (COALESCE(o.container_code,'')='' OR s.container_code=o.container_code)
			 AND s.quality_rework_operation_id IS NULL
			 AND ((o.action='HOLD' AND ((s.quality_hold_no IS NULL AND (s.status=0 OR (s.status=1 AND (s.quality_status='PENDING' OR s.is_quality_test='1')))) OR (s.quality_hold_no=o.hold_no AND s.quality_source_type=o.source_type AND s.quality_source_id=o.source_id AND s.quality_status IN ('HOLD','QUALIFIED','CONCESSION','SCRAP_PENDING','RETURN_PENDING')))) OR (o.action<>'HOLD' AND s.status=1 AND s.quality_hold_no=o.hold_no AND s.quality_source_type=o.source_type AND s.quality_source_id=o.source_id AND (s.quality_status='HOLD' OR (o.source_type<>'QMS_NCR' AND s.quality_status='REWORK'))))
			 WHERE o.frozen_source IS NULL AND o.special_return_binding_json IS NULL AND COALESCE(o.affected_stock_ids,'')='' AND COALESCE(o.warehouse_code,'')='' AND o.status='PENDING'
			)
			""";
}
