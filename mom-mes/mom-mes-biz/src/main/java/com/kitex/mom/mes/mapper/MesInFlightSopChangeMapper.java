package com.kitex.mom.mes.mapper;

import com.kitex.mom.mes.entity.*;
import org.apache.ibatis.annotations.*;
import java.util.List;

/** 显式组织范围，工单锁后读取并清理一级缓存；原工程与实绩只读。 */
@Mapper
public interface MesInFlightSopChangeMapper {
	@Select("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(CONCAT('MES_INFLIGHT_SOP_REQUEST:', #{tenant}, "
			+ "':', #{factory}, ':', #{request}), 0))")
	Integer lockRequest(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("request") String request);

	@Select("SELECT * FROM mom_mes.mes_inflight_sop_change_cancel WHERE tenant_id=#{tenant} AND "
			+ "factory_code=#{factory} AND request_id=#{request}")
	@Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
	MesInFlightSopChangeCancellationEntity cancelled(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("request") String request);

	@Insert("INSERT INTO mom_mes.mes_inflight_sop_change_cancel(tenant_id,factory_code,request_id,"
			+ "command_fingerprint,applicant_id,cancelled_at) VALUES(#{tenantId},#{factoryCode},#{requestId},"
			+ "#{commandFingerprint},#{applicantId},#{cancelledAt})")
	int cancel(MesInFlightSopChangeCancellationEntity row);

	@Select("SELECT * FROM mom_mes.mes_produce_task WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND "
			+ "id=#{id} AND del_flag='0'")
	@Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
	MesProduceTaskEntity process(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("id") Long id);

	@Select("SELECT * FROM mom_mes.mes_task WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND "
			+ "code=#{code} AND del_flag='0'")
	MesTaskEntity task(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("code") String code);

	@Select("SELECT * FROM mom_mes.mes_task_arrange WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND "
			+ "produce_task_code=#{code} AND del_flag='0' ORDER BY id")
	List<MesTaskArrangeEntity> arranges(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("code") String code);

	@Select("SELECT count(*) FROM mom_mes.mes_physical_transform_source_claim WHERE tenant_id=#{tenant} AND "
			+ "factory_code=#{factory} AND status='HELD' AND ((identity_type='TASK_CODE' AND identity_key=#{task}) "
			+ "OR (identity_type='PRODUCE_TASK_CODE' AND identity_key=#{process}))")
	long heldEngineering(@Param("tenant") Long tenant, @Param("factory") String factory,
			@Param("task") String task, @Param("process") String process);

	@Select("SELECT count(*) FROM mom_mes.mes_sop_execution_record WHERE tenant_id=#{tenant} AND "
			+ "factory_code=#{factory} AND produce_task_id=#{id}")
	long sopFacts(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("id") Long id);

	@Select("SELECT count(*) FROM mom_mes.mes_task_report WHERE tenant_id=#{tenant} AND factory_code=#{factory} "
			+ "AND produce_task_code=#{code}")
	long reports(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("code") String code);

	@Select("SELECT * FROM mom_mes.mes_inflight_sop_change WHERE tenant_id=#{tenant} AND factory_code=#{factory} "
			+ "AND request_id=#{request}")
	@Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
	MesInFlightSopChangeEntity request(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("request") String request);

	@Select("SELECT * FROM mom_mes.mes_inflight_sop_change WHERE tenant_id=#{tenant} AND factory_code=#{factory} "
			+ "AND id=#{id}")
	@Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
	MesInFlightSopChangeEntity change(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("id") Long id);

	@Select("SELECT * FROM mom_mes.mes_inflight_sop_change WHERE tenant_id=#{tenant} AND factory_code=#{factory} "
			+ "AND process_id=#{id} AND status='APPROVED'")
	@Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
	MesInFlightSopChangeEntity approved(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("id") Long id);

	@Select("SELECT * FROM mom_mes.mes_produce_task WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND "
			+ "task_code=#{task} AND del_flag='0' ORDER BY route_order,id")
	List<MesProduceTaskEntity> processes(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("task") String task);

	@Select("SELECT * FROM mom_mes.mes_inflight_sop_change WHERE tenant_id=#{tenant} AND factory_code=#{factory} "
			+ "AND task_code=#{task} ORDER BY proposed_at DESC,id DESC LIMIT 101")
	List<MesInFlightSopChangeEntity> changes(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("task") String task);

	@Insert("INSERT INTO mom_mes.mes_inflight_sop_change(id,tenant_id,factory_code,request_id,"
			+ "command_fingerprint,task_code,process_id,route_node_code,applicant_id,status,source_fingerprint,"
			+ "source_snapshot,release_id,content_hash,bundle_snapshot,sop_snapshot,reason,proposed_at) "
			+ "VALUES(#{id},#{tenantId},#{factoryCode},#{requestId},#{commandFingerprint},#{taskCode},#{processId},"
			+ "#{routeNodeCode},#{applicantId},#{status},#{sourceFingerprint},#{sourceSnapshot},#{releaseId},"
			+ "#{contentHash},#{bundleSnapshot},#{sopSnapshot},#{reason},#{proposedAt})")
	int insert(MesInFlightSopChangeEntity row);

	@Update("UPDATE mom_mes.mes_inflight_sop_change SET status=#{status},reviewer_id=#{reviewerId},"
			+ "review_reason=#{reviewReason},reviewed_at=#{reviewedAt} WHERE id=#{id} AND tenant_id=#{tenantId} "
			+ "AND factory_code=#{factoryCode} AND status='PENDING'")
	int review(MesInFlightSopChangeEntity row);
}
