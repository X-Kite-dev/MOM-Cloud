package com.kitex.mom.qms.agent.service;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 同工单检验→NCR→CAPA的真实分页和最终来源锁定。
 * 任务主键、工单主键/编码及物料批次逐项匹配，不能按相似物料或截断目录推断关联。
 */
@Repository
@RequiredArgsConstructor
public class QmsRootCauseTargetQuery {

	private final JdbcTemplate jdbc;

	private static final String SOURCE = " FROM mom_qms.qms_capa c JOIN mom_qms.qms_bad_info n"
			+ " ON n.id=c.source_id AND n.capa_id=c.id AND n.ncr_no=c.source_no"
			+ " AND n.tenant_id=c.tenant_id AND n.factory_code=c.factory_code AND n.del_flag='0'"
			+ " WHERE c.tenant_id=? AND c.factory_code=? AND c.del_flag='0' AND c.source_type='NCR'"
			+ " AND ((n.resource_type='PROCEDURE' AND EXISTS (SELECT 1 FROM mom_qms.qms_inspection_procedure_task t"
			+ " WHERE t.id=n.resource_task_id AND t.tenant_id=c.tenant_id AND t.factory_code=c.factory_code"
			+ " AND t.del_flag='0' AND t.order_no=? AND t.order_id=?"
			+ " AND t.mat_code IS NOT DISTINCT FROM n.mat_code AND t.batch_no IS NOT DISTINCT FROM n.batch_no))"
			+ " OR (n.resource_type='PRODUCT' AND EXISTS (SELECT 1 FROM mom_qms.qms_inspection_product_task t"
			+ " WHERE t.id=n.resource_task_id AND t.tenant_id=c.tenant_id AND t.factory_code=c.factory_code"
			+ " AND t.del_flag='0' AND t.order_no=? AND t.order_id=?"
			+ " AND t.mat_code IS NOT DISTINCT FROM n.mat_code AND t.batch_no IS NOT DISTINCT FROM n.batch_no)))";

	/** 数据库全范围COUNT，不使用工单质量工具原MAX200目录。 */
	public long count(Map<String, Object> proof, String capaNo) {
		return jdbc.queryForObject("SELECT count(*)" + SOURCE + filter(capaNo), Long.class, arguments(proof, capaNo).toArray());
	}

	/** 使用稳定原ID排序和真实offset分页，每页上限由编排入口限定。 */
	public List<Long> page(Map<String, Object> proof, String capaNo, long current, long size) {
		List<Object> arguments = arguments(proof, capaNo);
		arguments.add(size);
		arguments.add((current - 1) * size);
		return jdbc.queryForList("SELECT c.id" + SOURCE + filter(capaNo) + " ORDER BY c.id DESC LIMIT ? OFFSET ?",
				Long.class, arguments.toArray());
	}

	/**
	 * 原CAPA已持有锁后，再锁NCR和精确检验任务并重查双向关联。
	 * 并发业务若形成死锁由数据库回滚本次，原PREPARED保留，不能换号伪造成功。
	 */
	public void lockSource(Map<String, Object> proof, Long capaId) {
		List<Object> arguments = arguments(proof, null);
		arguments.add(capaId);
		List<Map<String, Object>> rows = jdbc.queryForList("SELECT n.id,n.resource_type,n.resource_task_id"
				+ SOURCE + " AND c.id=? FOR UPDATE OF n", arguments.toArray());
		if (rows.size() != 1) {
			throw new CheckedException("原CAPA、NCR或检验任务已变化，或不属于本次原工单，请重新选择");
		}
		Map<String, Object> ncr = rows.get(0);
		String table = "PROCEDURE".equals(ncr.get("resource_type"))
				? "mom_qms.qms_inspection_procedure_task" : "mom_qms.qms_inspection_product_task";
		List<Map<String, Object>> tasks = jdbc.queryForList("SELECT id FROM " + table
				+ " WHERE id=? AND tenant_id=? AND factory_code=? AND del_flag='0' AND order_no=? AND order_id=? FOR UPDATE",
				ncr.get("resource_task_id"), TenantContextHolder.getTenantId(), FactoryContextHolder.getFactoryCode(),
				proof.get("taskCode"), proof.get("taskId"));
		if (tasks.size() != 1 || jdbc.queryForObject("SELECT count(*)" + SOURCE + " AND c.id=?", Long.class,
				arguments.toArray()) != 1L) {
			throw new CheckedException("原检验工单、物料、批次或案件关联已变化，请重新读取");
		}
	}

	private String filter(String capaNo) {
		return capaNo == null || capaNo.isBlank() ? "" : " AND c.capa_no=?";
	}

	private List<Object> arguments(Map<String, Object> proof, String capaNo) {
		List<Object> result = new ArrayList<>(List.of(TenantContextHolder.getTenantId(), FactoryContextHolder.getFactoryCode(),
				proof.get("taskCode"), proof.get("taskId"), proof.get("taskCode"), proof.get("taskId")));
		if (capaNo != null && !capaNo.isBlank()) {
			result.add(capaNo);
		}
		return result;
	}
}
