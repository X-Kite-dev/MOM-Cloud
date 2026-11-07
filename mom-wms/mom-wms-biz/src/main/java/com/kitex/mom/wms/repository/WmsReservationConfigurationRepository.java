package com.kitex.mom.wms.repository;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.wms.dto.WmsReservationConfigurationDTO.Policy;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class WmsReservationConfigurationRepository {
	private final JdbcTemplate db;

	public void lock(Long tenant, String factory) {
		if (!Boolean.TRUE.equals(db.execute((ConnectionCallback<Boolean>) connection -> !connection.getAutoCommit()))
				|| !"read committed".equals(
				db.queryForObject("SELECT current_setting('transaction_isolation')", String.class))) {
			throw new CheckedException("预留配置需在READ_COMMITTED事务内读取和保存");
		}
		db.queryForObject(
				"SELECT 1 FROM pg_advisory_xact_lock(hashtextextended("
						+ "CONCAT('WMS_RESERVATION_CONFIGURATION:',?,':',?),0))",
				Integer.class, tenant, factory);
	}

	public List<Policy> list(Long tenant, String factory) {
		return db.query(
				"SELECT id,movement_type,reservation_allowed,status,remark "
						+ "FROM mom_wms.wms_reservation_movement_policy "
						+ "WHERE tenant_id=? AND factory_code=? AND del_flag='0' ORDER BY id LIMIT 10001",
				(row, n) -> new Policy(row.getString("id"), row.getString("movement_type"),
						row.getObject("reservation_allowed", Boolean.class), row.getObject("status", Boolean.class),
						row.getString("remark")), tenant, factory);
	}
}
