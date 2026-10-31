package com.kitex.mom.wms.repository;

import com.kitex.mom.wms.dto.WmsOrganizationTransferDTO.*;
import com.kitex.mom.wms.entity.organization.*;
import com.kitex.mom.wms.service.organization.WmsOrganizationTransferAccess.Scope;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.BeanPropertySqlParameterSource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * 协同台账有两端工厂列；所有SQL显式绑定租户和本侧仓库，不修改或跳过通用隔离上下文。
 */
@Repository
@RequiredArgsConstructor
public class WmsOrganizationTransferRepository {
	private final JdbcTemplate db;
	private static final String SIDE =
			"tenant_id=:tenant AND ((source_factory_code=:factory AND source_warehouse_code=:warehouse)"
					+ " OR (target_factory_code=:factory AND target_warehouse_code=:warehouse))";

	public void lock(Long tenant, String key) {
		db.queryForObject(
				"SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(CONCAT('WMS_ORG_TRANSFER:',?,':',?),0))",
				Integer.class, tenant, key);
	}

	public void lockWarehouse(Scope scope) {
		var rows = db.queryForList(
				"SELECT id FROM mom_mdm.mdm_warehouse WHERE tenant_id=? AND factory_code=? AND warehouse_code=? AND status=true AND del_flag='0' FOR UPDATE",
				scope.tenant(), scope.factory(), scope.warehouse());
		if (rows.size() != 1)
			throw new com.kitex.common.core.exception.CheckedException("实际仓库已停用、删除或不唯一，不能发布库存组织");
	}

	public void lockPolicies(Long tenant, Long sourceId, Long targetId) {
		db.queryForList(
				"SELECT id FROM mom_wms.wms_org_transfer_policy WHERE tenant_id=? AND id IN (?,?) ORDER BY id FOR UPDATE",
				tenant, sourceId, targetId);
	}

	public WmsOrganizationTransferPolicy policy(Long tenant, String factory, String warehouse) {
		return one(db.query(
				"SELECT * FROM mom_wms.wms_org_transfer_policy WHERE tenant_id=? AND factory_code=? AND warehouse_code=?",
				BeanPropertyRowMapper.newInstance(WmsOrganizationTransferPolicy.class), tenant, factory, warehouse));
	}

	public WmsOrganizationTransferPolicy policy(Long tenant, Long id) {
		return one(db.query("SELECT * FROM mom_wms.wms_org_transfer_policy WHERE tenant_id=? AND id=?",
				BeanPropertyRowMapper.newInstance(WmsOrganizationTransferPolicy.class), tenant, id));
	}

	public List<WmsOrganizationTransferPolicy> factoryPolicies(Long tenant, String factory) {
		return db.query("SELECT * FROM mom_wms.wms_org_transfer_policy WHERE tenant_id=? AND factory_code=?",
				BeanPropertyRowMapper.newInstance(WmsOrganizationTransferPolicy.class), tenant, factory);
	}

	public List<WmsOrganizationTransferPolicy> directory(Long tenant, String factory) {
		return db.query(
				"SELECT * FROM mom_wms.wms_org_transfer_policy WHERE tenant_id=? AND factory_code<>? AND enabled=true ORDER BY company_code,factory_code,warehouse_code LIMIT 1001",
				BeanPropertyRowMapper.newInstance(WmsOrganizationTransferPolicy.class), tenant, factory);
	}

	public int insertPolicy(WmsOrganizationTransferPolicy row) {
		return insert("wms_org_transfer_policy",
				"id,tenant_id,factory_code,warehouse_code,warehouse_name,company_code,company_name,enabled,cross_company,version,updated_by,updated_time",
				row);
	}

	public int updatePolicy(WmsOrganizationTransferPolicy row, int version) {
		return named().update(
				"UPDATE mom_wms.wms_org_transfer_policy SET warehouse_name=:warehouseName,company_name=:companyName,"
						+
						"enabled=:enabled,cross_company=:crossCompany,version=:version,updated_by=:updatedBy,updated_time=:updatedTime "
						+ "WHERE tenant_id=:tenantId AND id=:id AND version=" + version,
				new BeanPropertySqlParameterSource(row));
	}

	public WmsOrganizationTransferBill bill(Scope scope, Long id, boolean lock) {
		return one(named().query("SELECT * FROM mom_wms.wms_org_transfer_bill WHERE " + SIDE + " AND id=:id" +
						(lock ? " FOR UPDATE" : ""),
				params(scope).addValue("id", id),
				BeanPropertyRowMapper.newInstance(WmsOrganizationTransferBill.class)));
	}

	public Page page(Scope scope, String state, String billNo, int offset, int size) {
		String filter = " WHERE " + SIDE + " AND (:state='' OR state=:state) AND bill_no LIKE :billNo";
		var params = params(scope).addValue("state", state).addValue("billNo", "%" + billNo + "%").addValue("size",
				size).addValue("offset", offset);
		Long count = named().queryForObject("SELECT count(*) FROM mom_wms.wms_org_transfer_bill" + filter, params,
				Long.class);
		var rows = named().query("SELECT * FROM mom_wms.wms_org_transfer_bill" + filter +
						" ORDER BY updated_time DESC,id DESC LIMIT :size OFFSET :offset",
				params, BeanPropertyRowMapper.newInstance(WmsOrganizationTransferBill.class));
		return new Page(rows, count);
	}

	public int insertBill(WmsOrganizationTransferBill row) {
		return insert("wms_org_transfer_bill",
				"id,tenant_id,bill_no,source_policy_id,target_policy_id,source_factory_code,source_warehouse_code,source_warehouse_name,source_company_code,source_company_name,target_factory_code,target_warehouse_code,target_warehouse_name,target_company_code,target_company_name,state,previous_state,version,created_by,confirmed_by,created_by_id,confirmed_by_id,agreement_ref,remark,exception_reason,exception_evidence,created_time,updated_time",
				row);
	}

	public int updateBill(WmsOrganizationTransferBill row, int version) {
		return named().update(
				"UPDATE mom_wms.wms_org_transfer_bill SET state=:state,previous_state=:previousState,version=:version,"
						+
						"confirmed_by=:confirmedBy,confirmed_by_id=:confirmedById,exception_reason=:exceptionReason,exception_evidence=:exceptionEvidence,updated_time=:updatedTime "
						+ "WHERE tenant_id=:tenantId AND id=:id AND version=" + version,
				new BeanPropertySqlParameterSource(row));
	}

	public List<WmsOrganizationTransferLine> lines(Long tenant, Long billId) {
		return db.query("SELECT * FROM mom_wms.wms_org_transfer_line WHERE tenant_id=? AND bill_id=? ORDER BY id",
				BeanPropertyRowMapper.newInstance(WmsOrganizationTransferLine.class), tenant, billId);
	}

	public int insertLine(WmsOrganizationTransferLine row) {
		return insert("wms_org_transfer_line",
				"id,tenant_id,bill_id,source_stock_id,issue_flow_id,quantity,received_quantity,returned_quantity,exception_quantity,source_snapshot,issue_snapshot",
				row);
	}

	public int updateLine(WmsOrganizationTransferLine row) {
		return named().update(
				"UPDATE mom_wms.wms_org_transfer_line SET issue_flow_id=:issueFlowId,issue_snapshot=:issueSnapshot,"
						+
						"received_quantity=:receivedQuantity,returned_quantity=:returnedQuantity,exception_quantity=:exceptionQuantity "
						+ "WHERE tenant_id=:tenantId AND bill_id=:billId AND id=:id",
				new BeanPropertySqlParameterSource(row));
	}

	public Operation operation(Long tenant, String request) {
		return one(db.query("SELECT * FROM mom_wms.wms_org_transfer_operation WHERE tenant_id=? AND request_id=?",
				this::operation, tenant, request));
	}

	private Operation operation(ResultSet row, int index) throws SQLException {
		return new Operation(row.getLong("tenant_id"), row.getString("request_id"), row.getString("factory_code"),
				row.getString("warehouse_code"),
				row.getString("operator_code"), Action.valueOf(row.getString("action")),
				row.getObject("bill_id", Long.class),
				row.getString("command_payload"), row.getString("result_payload"), row.getString("status"),
				row.getTimestamp("completed_time").toLocalDateTime(), row.getLong("operator_id"));
	}

	public int insertOperation(Operation row) {
		return db.update(
				"INSERT INTO mom_wms.wms_org_transfer_operation(tenant_id,request_id,factory_code,warehouse_code,operator_code,action,bill_id,command_payload,result_payload,status,completed_time,operator_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
				row.tenantId(), row.requestId(), row.factoryCode(), row.warehouseCode(), row.operatorCode(),
				row.action().name(), row.billId(),
				row.commandPayload(), row.resultPayload(), row.status(), row.completedTime(), row.operatorId());
	}

	public int insertEffect(Long tenant, Long billId, String request, Effect row, Long id) {
		return db.update(
				"INSERT INTO mom_wms.wms_org_transfer_effect(id,tenant_id,bill_id,line_id,request_id,factory_code,warehouse_code,action,stock_id,flow_id,quantity,time) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
				id, tenant, billId, row.lineId(), request, row.factoryCode(), row.warehouseCode(), row.action(),
				row.stockId(), row.flowId(), row.quantity(), row.time());
	}

	public List<Effect> effects(Long tenant, Long billId, int offset, int size) {
		return db.query(
				"SELECT * FROM mom_wms.wms_org_transfer_effect WHERE tenant_id=? AND bill_id=? ORDER BY time DESC,id DESC LIMIT ? OFFSET ?",
				(row, i) -> new Effect(row.getLong("line_id"), row.getString("factory_code"),
						row.getString("warehouse_code"), row.getString("action"),
						row.getLong("stock_id"), row.getLong("flow_id"), row.getBigDecimal("quantity"),
						row.getTimestamp("time").toLocalDateTime()), tenant, billId, size, offset);
	}

	public List<Operation> history(Long tenant, Long billId, int offset, int size) {
		return db.query(
				"SELECT * FROM mom_wms.wms_org_transfer_operation WHERE tenant_id=? AND bill_id=? AND status='APPLIED' ORDER BY completed_time DESC,request_id DESC LIMIT ? OFFSET ?",
				this::operation, tenant, billId, size, offset);
	}

	public List<com.kitex.mom.wms.dto.WmsStockTraceDTO.OrganizationLink> traceLinks(Long tenant, String factory,
	                                                                                java.util.Set<String> warehouses,
	                                                                                List<Long> flowIds) {
		if (warehouses.isEmpty() || flowIds.isEmpty()) return List.of();
		return named().query(
				"SELECT e.flow_id,b.id,b.bill_no,b.source_company_code,b.source_factory_code,b.source_warehouse_code,b.target_company_code,b.target_factory_code,b.target_warehouse_code,e.action,e.quantity FROM mom_wms.wms_org_transfer_effect e JOIN mom_wms.wms_org_transfer_bill b ON b.tenant_id=e.tenant_id AND b.id=e.bill_id WHERE e.tenant_id=:tenant AND e.factory_code=:factory AND e.warehouse_code IN (:warehouses) AND e.flow_id IN (:flows)",
				new MapSqlParameterSource(
						Map.of("tenant", tenant, "factory", factory, "warehouses", warehouses, "flows", flowIds)),
				(row, i) -> new com.kitex.mom.wms.dto.WmsStockTraceDTO.OrganizationLink(row.getLong("flow_id"),
						row.getLong("id"), row.getString("bill_no"), row.getString("source_company_code"),
						row.getString("source_factory_code"), row.getString("source_warehouse_code"),
						row.getString("target_company_code"), row.getString("target_factory_code"),
						row.getString("target_warehouse_code"), row.getString("action"),
						row.getBigDecimal("quantity")));
	}

	private int insert(String table, String columns, Object row) {
		String values = java.util.Arrays.stream(columns.split(",")).map(name -> ":" + camel(name)).collect(
				java.util.stream.Collectors.joining(","));
		return named().update("INSERT INTO mom_wms." + table + "(" + columns + ") VALUES(" + values + ")",
				new BeanPropertySqlParameterSource(row));
	}

	private String camel(String name) {
		var parts = name.split("_");
		var out = new StringBuilder(parts[0]);
		for (int i = 1; i < parts.length; i++)
			out.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));
		return out.toString();
	}

	private NamedParameterJdbcTemplate named() {
		return new NamedParameterJdbcTemplate(db);
	}

	private MapSqlParameterSource params(Scope scope) {
		return new MapSqlParameterSource(
				Map.of("tenant", scope.tenant(), "factory", scope.factory(), "warehouse", scope.warehouse()));
	}

	private <T> T one(List<T> rows) {
		if (rows.size() > 1) throw new IllegalStateException("组织调拨身份查询出现重复数据");
		return rows.isEmpty() ? null : rows.get(0);
	}
}
