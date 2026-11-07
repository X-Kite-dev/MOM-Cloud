package com.kitex.mom.wms.repository;

import com.kitex.mom.wms.dto.WmsWarehouseTurnoverDTO.Balance;
import com.kitex.mom.wms.dto.WmsWarehouseTurnoverDTO.Issue;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 历史库存与当前有效毛出库的精确组织查询；不截断物料分组。
 */
@Repository
@RequiredArgsConstructor
public class WmsWarehouseTurnoverRepository {
	private final JdbcTemplate jdbc;

	public LocalDateTime origin() {
		return jdbc.queryForObject("SELECT started_at FROM mom_wms.wms_inventory_history_origin WHERE singleton "
				+ "AND (SELECT COUNT(*) FROM pg_trigger WHERE tgrelid='mom_wms.wms_live_stock'::regclass "
				+ "AND tgname IN ('wms_inventory_history_capture','wms_inventory_stock_truncate') AND tgenabled='A')=2", LocalDateTime.class);
	}

	public LocalDateTime now() {
		return jdbc.queryForObject("SELECT transaction_timestamp() AT TIME ZONE 'Asia/Shanghai'", LocalDateTime.class);
	}

	public List<Balance> balances(long tenant, String factory, String warehouse, LocalDate from, LocalDate to, String material) {
		String sql = """
				WITH keys AS (
				 SELECT DISTINCT mat_code,mat_unit FROM mom_wms.wms_inventory_history
				 WHERE tenant_id=? AND factory_code=? AND warehouse_code=? AND occurred_at<?
				 AND (CAST(? AS text) IS NULL OR mat_code=?) ORDER BY mat_code,mat_unit LIMIT 1001
				), days AS (SELECT generate_series(CAST(? AS date),CAST(? AS date),interval '1 day') AS day)
				SELECT k.mat_code,k.mat_unit,d.day,COALESCE(SUM(h.quantity_delta),0)::text AS quantity,
				 COALESCE(SUM(h.invalid_delta),0) AS invalid_rows
				FROM keys k CROSS JOIN days d LEFT JOIN mom_wms.wms_inventory_history h
				 ON h.tenant_id=? AND h.factory_code=? AND h.warehouse_code=? AND h.mat_code=k.mat_code AND h.mat_unit=k.mat_unit
				 AND h.occurred_at<d.day+interval '1 day'
				GROUP BY k.mat_code,k.mat_unit,d.day ORDER BY k.mat_code,k.mat_unit,d.day
				""";
		return jdbc.query(sql, (rs, index) -> new Balance(rs.getString("mat_code"), rs.getString("mat_unit"),
						rs.getDate("day").toLocalDate(), rs.getString("quantity"), rs.getLong("invalid_rows")),
				tenant, factory, warehouse, to.plusDays(1).atStartOfDay(), material, material, from.minusDays(1), to, tenant, factory, warehouse);
	}

	public List<Issue> issues(long tenant, String factory, String warehouse, LocalDate from, LocalDate to, String basis, String material) {
		String valid = "create_time IS NOT NULL AND mat_num > 0 AND CAST(mat_num AS text) NOT IN ('NaN','Infinity','-Infinity')";
		String sql = "SELECT COALESCE(mat_code,'') AS mat_code,COALESCE(mat_unit,'') AS mat_unit, "
				+ "SUM(CASE WHEN " + valid + " THEN CAST(CAST(mat_num AS text) AS numeric) ELSE 0 END)::text AS quantity, "
				+ "SUM(CASE WHEN mat_num IS NULL OR NOT (" + valid + ") THEN 1 ELSE 0 END) AS invalid_rows "
				+ "FROM mom_wms.wms_inout_flow WHERE tenant_id=? AND factory_code=? AND warehouse_code=? AND del_flag='0' "
				+ "AND operate_type='out' AND (create_time IS NULL OR (create_time>=? AND create_time<?)) AND (CAST(? AS text) IS NULL OR mat_code=?) "
				+ "AND ((? IN ('SALES','BOTH') AND bill_type='sales') OR (? IN ('PRODUCTION','BOTH') AND bill_type='produce')) "
				+ "GROUP BY COALESCE(mat_code,''),COALESCE(mat_unit,'') ORDER BY COALESCE(mat_code,''),COALESCE(mat_unit,'') LIMIT 1001";
		return jdbc.query(sql, (rs, index) -> new Issue(rs.getString("mat_code"), rs.getString("mat_unit"),
						rs.getString("quantity"), rs.getLong("invalid_rows")), tenant, factory, warehouse, from.atStartOfDay(),
				to.plusDays(1).atStartOfDay(), material, material, basis, basis);
	}
}
