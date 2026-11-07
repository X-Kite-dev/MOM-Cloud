package com.kitex.mom.qms.agent.service;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.qms.agent.dto.QmsRootCauseImportDTO.Command;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * AI根因引用原号的本地持久仓储；所有查询显式限定认证租户、实际工厂。
 * 无通用编辑/删除接口，状态与来源保护另由824数据库守卫保证。
 */
@Repository
@RequiredArgsConstructor
public class QmsRootCauseRequestStore {

	private final JdbcTemplate jdbc;

	/** 判断可选来源账是否存在；原人工验证在未安装824时保持原合同。 */
	public boolean exists() {
		return jdbc.queryForObject("SELECT to_regclass('mom_qms.qms_capa_ai_cause_request')::text", String.class) != null;
	}

	/** 仅检查本地目录；缺少专项时普通人工根因仍可查询，不调用AI。 */
	public boolean installed() {
		String function = jdbc.queryForObject("SELECT to_regprocedure('mom_qms.qms_ai_cause_request_ready_824()')::text", String.class);
		return function != null && Boolean.TRUE.equals(jdbc.queryForObject(
				"SELECT mom_qms.qms_ai_cause_request_ready_824()", Boolean.class));
	}

	/** 新引用必须有完整824安装，未知或缺保护的目录明确拒绝。 */
	public void requireInstalled() {
		if (!installed()) {
			throw new CheckedException("质量假设引用尚未安装完整AI专项824，请联系实施人员核查；普通CAPA办理不受影响");
		}
	}

	/** 与准备、接受和取消共用事务级原号栅栏，阻止迟到POST跨过取消终态。 */
	public void lock(String requestId) {
		jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", Object.class,
				"QMS-AI-CAUSE:" + tenant() + ":" + factory() + ":" + requestId);
	}

	/** 原组织精确原号读取，FOR UPDATE仅在命令事务使用。 */
	public Map<String, Object> find(String requestId, boolean lock) {
		List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM mom_qms.qms_capa_ai_cause_request"
				+ " WHERE tenant_id=? AND factory_code=? AND request_id=?" + (lock ? " FOR UPDATE" : ""),
				tenant(), factory(), requestId);
		return rows.isEmpty() ? null : rows.get(0);
	}

	/** 原完整命令先短事务持久化；取消首次未命中也保存完整命令永久封存。 */
	public void insert(Command command, String capaNo, String commandHash, String commandJson, String status) {
		changed(jdbc.update("INSERT INTO mom_qms.qms_capa_ai_cause_request"
				+ " (tenant_id,factory_code,request_id,actor_user_id,actor_code,command_hash,command_json,"
				+ "capa_id,capa_no,cycle_no,case_fingerprint,run_id,analysis_artifact_id,analysis_content_hash,hypothesis_id,"
				+ "analysis_method,cause_description,evidence,status,create_by,create_time,terminal_time)"
				+ " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,localtimestamp,"
				+ ("CANCELLED".equals(status) ? "localtimestamp" : "NULL") + ")",
				tenant(), factory(), command.requestId(), actor(), SecurityUtils.getUser().getUsername(), commandHash,
				commandJson, Long.valueOf(command.capaId()), capaNo, command.expectedCycleNo(), command.expectedCaseFingerprint(),
				Long.valueOf(command.runId()), Long.valueOf(command.analysisArtifactId()), command.analysisContentHash(),
				command.hypothesisId(), command.analysisMethod(), command.causeDescription(), command.evidence(), status,
				SecurityUtils.getUser().getUsername()));
	}

	/** PREPARED唯一合法终态之一；影响行数不为1时必须回滚。 */
	public void cancel(String requestId) {
		changed(jdbc.update("UPDATE mom_qms.qms_capa_ai_cause_request SET status='CANCELLED',terminal_time=localtimestamp"
				+ " WHERE tenant_id=? AND factory_code=? AND request_id=? AND status='PREPARED'",
				tenant(), factory(), requestId));
	}

	/** 源证明、真实原根因和ACCEPTED同事务一次落账，不单独覆盖证明。 */
	public void accept(String requestId, Map<String, Object> proof, String proofJson, String proofHash,
			String sourcePermissionsJson, Long causeId, String causeNo) {
		changed(jdbc.update("UPDATE mom_qms.qms_capa_ai_cause_request SET status='ACCEPTED',"
				+ "source_proof_json=?,proof_hash=?,source_permissions_json=?,evidence_artifact_id=?,evidence_content_hash=?,"
				+ "source_context_hash=?,source_task_id=?,source_task_code=?,hypothesis_kind=?,cause_id=?,cause_no=?,"
				+ "terminal_time=localtimestamp WHERE tenant_id=? AND factory_code=? AND request_id=? AND status='PREPARED'",
				proofJson, proofHash, sourcePermissionsJson, Long.valueOf(proof.get("evidenceArtifactId").toString()),
				proof.get("evidenceContentHash"), proof.get("sourceContextHash"), Long.valueOf(proof.get("taskId").toString()),
				proof.get("taskCode"), proof.get("hypothesisKind"), causeId, causeNo, tenant(), factory(), requestId));
	}

	/** 同一周期同一保存假设只能受理一次，不能换请求号重提。 */
	public boolean accepted(Command command) {
		return !jdbc.queryForList("SELECT request_id FROM mom_qms.qms_capa_ai_cause_request"
				+ " WHERE tenant_id=? AND factory_code=? AND capa_id=? AND cycle_no=?"
				+ " AND analysis_artifact_id=? AND hypothesis_id=? AND status='ACCEPTED'",
				tenant(), factory(), Long.valueOf(command.capaId()), command.expectedCycleNo(),
				Long.valueOf(command.analysisArtifactId()), command.hypothesisId()).isEmpty();
	}

	/** 原根因精确本地来源读取，不依赖AI在线。 */
	public Map<String, Object> source(Long causeId, Long capaId) {
		List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM mom_qms.qms_capa_ai_cause_request"
				+ " WHERE tenant_id=? AND factory_code=? AND capa_id=? AND cause_id=? AND status='ACCEPTED'",
				tenant(), factory(), capaId, causeId);
		return rows.isEmpty() ? null : rows.get(0);
	}

	/** 原根因列表一次性读取来源摘要，避免逐行查询和AI网络依赖。 */
	public List<Map<String, Object>> sources(List<Long> causeIds) {
		if (causeIds.isEmpty()) {
			return List.of();
		}
		List<Object> arguments = new java.util.ArrayList<>(List.of(tenant(), factory()));
		arguments.addAll(causeIds);
		String placeholders = String.join(",", java.util.Collections.nCopies(causeIds.size(), "?"));
		return jdbc.queryForList("SELECT request_id,run_id,analysis_artifact_id,hypothesis_id,cause_id,capa_id"
				+ " FROM mom_qms.qms_capa_ai_cause_request WHERE tenant_id=? AND factory_code=?"
				+ " AND status='ACCEPTED' AND cause_id IN (" + placeholders + ")", arguments.toArray());
	}

	private void changed(int count) {
		if (count != 1) {
			throw new CheckedException("原质量引用请求已变化，未保存新的受理结果，请沿原号核查");
		}
	}

	private Long tenant() {
		return TenantContextHolder.getTenantId();
	}

	private String factory() {
		return FactoryContextHolder.getFactoryCode();
	}

	private String actor() {
		return SecurityUtils.getUser().getId().toString();
	}
}
