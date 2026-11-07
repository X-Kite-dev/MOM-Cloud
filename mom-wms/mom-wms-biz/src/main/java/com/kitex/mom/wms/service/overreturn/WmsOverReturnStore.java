package com.kitex.mom.wms.service.overreturn;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.kitex.mom.wms.dto.WmsOverReturnDTO.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

import static com.kitex.mom.wms.service.overreturn.WmsOverReturnRules.*;

/**
 * 两张不可变返还账的持久化；全部查询显式限定租户和工厂，原号锁覆盖跨案仲裁。
 */
@Repository
@RequiredArgsConstructor
public class WmsOverReturnStore {
	private final JdbcTemplate jdbc;

	/**
	 * 验证本域数据库保护完整就绪，缺章或漂移拒绝新写。
	 */
	public void ready() {
		require(Boolean.TRUE.equals(jdbc.queryForObject("select mom_wms.over_return_ready_401()", Boolean.class)),
				"溢收返还数据库保护未就绪");
	}

	/**
	 * 在同一事务声明本次实际收货原号，仅给本作业新增库存建立数据库插入见证。
	 * 该意向不能代替实际库存、原流水及回执校验，事务结束后自动清除。
	 */
	public void receiveIntent(String request) {
		require(request.equals(jdbc.queryForObject("select set_config('mom_wms.over_return_request',?,true)",
				String.class, request)), "实收作业意向设置失败");
	}

	/**
	 * 按实际租户和原号串行仲裁，跨案并发也共用同锁域。
	 */
	public void lock(Long tenant, String request) {
		jdbc.queryForList("select pg_advisory_xact_lock(hashtextextended(?,0))",
				"WMS_OVER_RETURN:" + tenant + ":" + request);
	}

	/**
	 * 读取当前组织原案的不可变逐份额原件。
	 */
	public List<Map<String, Object>> manifests(Long tenant, String factory, Long caseId) {
		return jdbc.queryForList(
				"select * from mom_wms.wms_over_return_manifest where tenant_id=? and factory_code=? and case_id=? order by adjustment_flow_id",
				tenant, factory, caseId);
	}

	/**
	 * 按租户原号读取永久事实，不以未找到以外错误冒充空结果。
	 */
	public Map<String, Object> request(Long tenant, String request) {
		var rows = jdbc.queryForList("select * from mom_wms.wms_over_return_request where tenant_id=? and request_id=?",
				tenant, request);
		require(rows.size() <= 1, "原号账重复");
		return rows.isEmpty() ? null : rows.get(0);
	}

	/**
	 * 从真实OVER_RETURN累计该原补扣片段数量。
	 */
	public BigDecimal received(Long tenant, Long flow) {
		return jdbc.queryForObject(
				"select coalesce(sum(mat_num),0) from mom_wms.wms_inout_flow where tenant_id=? and factory_code=? and source_flow_id=? and transfer_action='OVER_RETURN' and del_flag='0'",
				BigDecimal.class, tenant, com.kitex.common.data.orgFilter.FactoryContextHolder.getFactoryCode(), flow);
	}

	/**
	 * 读取原流水数据库精确数量，核旧Double能否无损表示。
	 */
	public BigDecimal actualQuantity(Long tenant, Long flow) {
		return jdbc.queryForObject(
				"select mat_num from mom_wms.wms_inout_flow where tenant_id=? and factory_code=? and id=? and del_flag='0'",
				BigDecimal.class, tenant, com.kitex.common.data.orgFilter.FactoryContextHolder.getFactoryCode(), flow);
	}

	/**
	 * 在原处置事务中插入完整原件及同一派出事件。
	 */
	public void manifest(long id, WmsOverReturnAccess.Scope s, long caseId, long billId, long lineId, long flow,
	                     String source, String target, String request, BigDecimal q, String snapshot, String evidence,
	                     LocalDateTime time) {
		require(jdbc.update(
				"insert into mom_wms.wms_over_return_manifest(id,tenant_id,factory_code,case_id,bill_id,variance_line_id,adjustment_flow_id,source_warehouse_code,target_warehouse_code,dispatch_request_id,dispatched_quantity,source_snapshot,dispatch_evidence,actor_id,dispatch_time,create_by,update_by,create_time,update_time,del_flag) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'0')",
				id, s.tenant(), s.factory(), caseId, billId, lineId, flow, source, target, request, q, snapshot,
				evidence, s.actor(), time, s.operator(), s.operator(), time, time) == 1, "返还原件保存失败");
	}

	/**
	 * 一次插入完整永久终态与原封命令，禁止后续更新。
	 */
	public void save(long id, WmsOverReturnAccess.Scope s, Envelope envelope, Command command, Receipt receipt,
	                 String effects, LocalDateTime time) {
		require(jdbc.update(
				"insert into mom_wms.wms_over_return_request(id,tenant_id,factory_code,case_id,bill_id,source_warehouse_code,request_id,actor_id,operator_name,command_text,status,handled_time,after_status,effects_json,create_by,update_by,create_time,update_time,del_flag) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'0')",
				id, s.tenant(), s.factory(), Long.valueOf(receipt.caseId()), Long.valueOf(receipt.billId()),
				command.warehouseCode(), envelope.requestId(), s.actor(), receipt.operatorName(),
				envelope.commandText(), receipt.status(), time, receipt.afterStatus(), effects, s.operator(),
				s.operator(), time, time) == 1, "永久返还回执保存失败");
	}

	/**
	 * 回读本案正式APPLIED履历，不向客户端暴露他人命令全文。
	 */
	public List<Map<String, Object>> receipts(Long tenant, String factory, Long caseId) {
		return jdbc.queryForList(
				"select * from mom_wms.wms_over_return_request where tenant_id=? and factory_code=? and case_id=? and status='APPLIED' order by handled_time,id",
				tenant, factory, caseId);
	}

	/**
	 * 在原仓与查询条件过滤之后分页或计数。
	 */
	public List<Map<String, Object>> pending(Long tenant, String factory, String warehouse, String scope, String billNo,
	                                         String barcode, long size, long offset, boolean count) {
		String where = " from mom_wms.wms_transfer_variance_case c where c.tenant_id=? and c.factory_code=? and c.source_warehouse_code=? and c.del_flag='0' and exists(select 1 from mom_wms.wms_over_return_manifest m where m.tenant_id=c.tenant_id and m.case_id=c.id";
		List<Object> args = new ArrayList<>(List.of(tenant, factory, warehouse));
		if (barcode != null && !barcode.isBlank()) {
			where += " and m.source_snapshot::jsonb->>'matBarcode'=?";
			args.add(barcode);
		}
		where += ")";
		if (!"HISTORY".equals(scope)) {
			where += " and c.status in ('RETURN_IN_TRANSIT','RETURN_PARTIAL')";
		}
		if (billNo != null && !billNo.isBlank()) {
			where += " and c.allocation_bill_no like ?";
			args.add("%" + billNo + "%");
		}
		String select = count ? "select count(*) as total" : "select c.*, (select min(dispatch_time) from mom_wms.wms_over_return_manifest m where m.tenant_id=c.tenant_id and m.case_id=c.id) as dispatch_time";
		if (!count) {
			where += " order by dispatch_time,c.id limit ? offset ?";
			args.add(size);
			args.add(offset);
		}
		return jdbc.queryForList(select + where, args.toArray());
	}

	/**
	 * 读取数据库可空文本，ID不转浮点数。
	 */
	public static String str(Map<String, Object> row, String key) {
		Object value = row.get(key);
		return value == null ? null : value.toString();
	}

	/**
	 * 读取内部数据库整数，不用于跨端JSON number。
	 */
	public static long number(Map<String, Object> row, String key) {
		return ((Number) row.get(key)).longValue();
	}
}
