package com.kitex.mom.wms.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 显式组织范围的预警事件、策略和处理履历；后台不得依赖登录用户过滤。
 */
@Mapper
@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
public interface WmsInventoryCountEventMapper {
	String POLICY = """
			SELECT version, enabled, expiration, slow_moving AS slowMoving, start_event_id AS startEventId,
			updated_by AS updatedBy FROM mom_wms.wms_inventory_count_event_policy
			""";

	@Select(POLICY +
			"WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND " +
			"template_code=#{code} AND warehouse_code=#{warehouse}")
	PolicyRow policy(@Param("tenant") Long tenant, @Param("factory") String factory,
	                 @Param("warehouse") String warehouse, @Param("code") String code);

	@Select(POLICY +
			"WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND " +
			"template_code=#{code} AND warehouse_code=#{warehouse} FOR UPDATE")
	PolicyRow lockPolicy(@Param("tenant") Long tenant, @Param("factory") String factory,
	                     @Param("warehouse") String warehouse, @Param("code") String code);

	@Select("""
			SELECT nextval('mom_wms.wms_count_event_seq')
			""")
	Long boundary();

	@Insert("""
			INSERT INTO mom_wms.wms_inventory_count_event_policy AS p (tenant_id, factory_code, warehouse_code,
			template_code, version, enabled, expiration, slow_moving, start_event_id, updated_by) VALUES(#{tenant},
			#{factory}, #{warehouse}, #{code}, 1, #{enabled}, #{expiration}, #{slow}, #{start}, #{actor}) ON
			CONFLICT(tenant_id, factory_code, template_code) DO UPDATE SET version=p.version+1,
			enabled=EXCLUDED.enabled, expiration=EXCLUDED.expiration, slow_moving=EXCLUDED.slow_moving,
			start_event_id=EXCLUDED.start_event_id, updated_by=EXCLUDED.updated_by WHERE p.version=#{expected} AND
			p.warehouse_code=EXCLUDED.warehouse_code
			""")
	int savePolicy(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("warehouse") String warehouse,
	               @Param("code") String code, @Param("enabled") boolean enabled,
	               @Param("expiration") boolean expiration, @Param("slow") boolean slow, @Param("start") Long start,
	               @Param("actor") String actor, @Param("expected") int expected);

	String EVENT = """
			SELECT id, tenant_id AS tenantId, factory_code AS factoryCode, warehouse_code AS warehouseCode,
			warning_id AS warningId, warning_no AS warningNo, event_type AS eventType, mat_code AS matCode, site_code
			AS siteCode, mat_batch AS matBatch, mat_unit AS matUnit, to_char(occurred_at, 'YYYY-MM-DD HH24:MI:SS') AS
			occurredTime FROM mom_wms.wms_inventory_count_event
			""";

	@Select(EVENT +
			"WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND warehouse_code=#{warehouse} AND id=#{eventId}")
	EventRow event(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("warehouse") String warehouse,
	               @Param("eventId") Long eventId);

	String RESULT = """
			SELECT event_id AS eventId, status, plan_id AS planId, plan_no AS planNo, attempts, message,
			policy_version AS policyVersion, operator_code AS operatorCode, to_char(operated_at,
			'YYYY-MM-DD HH24:MI:SS') AS operatedTime FROM mom_wms.wms_inventory_count_event_run
			""";

	@Select(RESULT +
			"WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND " +
			"warehouse_code=#{warehouse} AND template_code=#{code} AND event_id=#{eventId}")
	ResultRow result(@Param("tenant") Long tenant, @Param("factory") String factory,
	                 @Param("warehouse") String warehouse, @Param("code") String code, @Param("eventId") Long eventId);

	@Select("""
			SELECT p.template_code AS templateCode, p.warehouse_code AS warehouseCode, e.id AS eventId FROM
			mom_wms.wms_inventory_count_event_policy p JOIN mom_wms.wms_inventory_count_template t ON
			t.tenant_id=p.tenant_id AND t.factory_code=p.factory_code AND t.template_code=p.template_code AND
			t.warehouse_code=p.warehouse_code JOIN mom_wms.wms_inventory_count_event e ON e.tenant_id=p.tenant_id AND
			e.factory_code=p.factory_code AND e.warehouse_code=p.warehouse_code AND e.id>p.start_event_id LEFT JOIN
			mom_wms.wms_inventory_count_event_run r ON r.tenant_id=p.tenant_id AND r.factory_code=p.factory_code AND
			r.template_code=p.template_code AND r.event_id=e.id WHERE p.tenant_id=#{tenant} AND
			p.factory_code=#{factory} AND p.enabled AND t.enabled AND ((p.expiration AND e.event_type='EXPIRATION')
			OR (p.slow_moving AND e.event_type='SLOW_MOVING')) AND (#{warehouse,jdbcType=VARCHAR} IS NULL OR
			p.warehouse_code=#{warehouse}) AND (#{code,jdbcType=VARCHAR} IS NULL OR p.template_code=#{code}) AND
			(r.event_id IS NULL OR (r.status='FAILED' AND (#{force} OR r.retry_after<=timezone('UTC',
			clock_timestamp()))))
			         ORDER BY coalesce(r.retry_after,e.occurred_at), e.id, p.template_code LIMIT #{limit}
			""")
	List<Candidate> pending(@Param("tenant") Long tenant, @Param("factory") String factory,
	                        @Param("warehouse") String warehouse, @Param("code") String code,
	                        @Param("force") boolean force, @Param("limit") int limit);

	@Insert("""
			INSERT INTO mom_wms.wms_inventory_count_event_run AS r (tenant_id, factory_code, warehouse_code,
			template_code, event_id, status, plan_id, plan_no, attempts, message, policy_version, policy_json,
			template_json, operator_code, retry_after) VALUES(#{tenant}, #{factory}, #{warehouse}, #{code},
			#{eventId}, #{status}, #{planId}, #{planNo}, 1, #{message}, #{version}, #{policyJson}, #{templateJson},
			#{actor}, CASE WHEN #{status}='FAILED' THEN timezone('UTC', clock_timestamp())+interval '5 minutes' ELSE
			NULL END) ON CONFLICT(tenant_id, factory_code, template_code, event_id) DO UPDATE SET
			status=EXCLUDED.status, plan_id=EXCLUDED.plan_id, plan_no=EXCLUDED.plan_no, attempts=r.attempts+1,
			message=EXCLUDED.message, policy_version=EXCLUDED.policy_version, policy_json=EXCLUDED.policy_json,
			template_json=EXCLUDED.template_json, operator_code=EXCLUDED.operator_code,
			retry_after=EXCLUDED.retry_after, operated_at=timezone('UTC', clock_timestamp()) WHERE r.status='FAILED'
			""")
	int record(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("warehouse") String warehouse,
	           @Param("code") String code, @Param("eventId") Long eventId, @Param("status") String status,
	           @Param("planId") Long planId, @Param("planNo") String planNo, @Param("message") String message,
	           @Param("version") int version, @Param("policyJson") String policyJson,
	           @Param("templateJson") String templateJson, @Param("actor") String actor);

	@Select("""
			SELECT e.id AS eventId, e.warning_no AS warningNo, e.event_type AS eventType, e.mat_code AS matCode,
			e.site_code AS siteCode, e.mat_batch AS matBatch, r.status, r.plan_id AS planId, r.plan_no AS planNo,
			r.attempts, r.message, r.policy_version AS policyVersion, r.operator_code AS operatorCode,
			to_char(r.operated_at, 'YYYY-MM-DD HH24:MI:SS') AS operatedTime FROM
			mom_wms.wms_inventory_count_event_run r JOIN mom_wms.wms_inventory_count_event e ON
			e.tenant_id=r.tenant_id AND e.factory_code=r.factory_code AND e.warehouse_code=r.warehouse_code AND
			e.id=r.event_id WHERE r.tenant_id=#{tenant} AND r.factory_code=#{factory} AND
			r.warehouse_code=#{warehouse} AND r.template_code=#{code} ORDER BY r.operated_at DESC, e.id DESC LIMIT 20
			""")
	List<HistoryRow> history(@Param("tenant") Long tenant, @Param("factory") String factory,
	                         @Param("warehouse") String warehouse, @Param("code") String code);

	record PolicyRow(int version, boolean enabled, boolean expiration, boolean slowMoving, Long startEventId,
	                 String updatedBy) {
	}

	record EventRow(Long id, Long tenantId, String factoryCode, String warehouseCode, Long warningId, String warningNo,
	                String eventType, String matCode, String siteCode, String matBatch, String matUnit,
	                String occurredTime) {
	}

	record Candidate(String templateCode, String warehouseCode, Long eventId) {
	}

	record ResultRow(Long eventId, String status, Long planId, String planNo, int attempts, String message,
	                 int policyVersion, String operatorCode, String operatedTime) {
	}

	record HistoryRow(Long eventId, String warningNo, String eventType, String matCode, String siteCode,
	                  String matBatch, String status, Long planId, String planNo, int attempts, String message,
	                  int policyVersion, String operatorCode, String operatedTime) {
	}
}
