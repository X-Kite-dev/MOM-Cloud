package com.kitex.mom.mes.mapper;

import com.kitex.mom.mes.entity.*;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 独立手工工单工时批准与接收采用账；读取显式限定租户工厂，锁后清理一级缓存。
 */
@Mapper
public interface MesManualLaborChangeMapper {
	/**
	 * 只查catalog，不静态解析尚未安装的函数或新表。
	 */
	@Select("SELECT to_regprocedure('mom_mes.mom_manual_labor_change_ready_v1()') IS NOT NULL")
	Boolean readyPresent();

	/**
	 * 只读目录探测本节任何同名对象；只残留helper/索引/类型也属于部分安装，不能冒充完全未装。
	 */
	@Select("""
			SELECT EXISTS (
				SELECT 1 FROM pg_catalog.pg_class c
				JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
				WHERE n.nspname='mom_mes' AND c.relname IN (
					'mes_manual_labor_change','mes_manual_labor_adoption',
					'uq_mes_manual_labor_approved','ix_mes_manual_labor_task','ix_mes_manual_labor_adoption_node'
				)
			) OR EXISTS (
				SELECT 1 FROM pg_catalog.pg_proc p
				JOIN pg_catalog.pg_namespace n ON n.oid=p.pronamespace
				WHERE n.nspname='mom_mes' AND p.proname IN (
					'manual_labor_no_plan_v1','manual_labor_lock_task_v1','manual_labor_without_node_v1',
					'manual_labor_standard_valid_v1','manual_labor_original_v1','guard_manual_labor_change_v1',
					'guard_manual_labor_adoption_v1','commit_manual_labor_adoption_v1','mom_manual_labor_change_ready_v1'
				)
			) OR EXISTS (
				SELECT 1 FROM pg_catalog.pg_type t
				JOIN pg_catalog.pg_namespace n ON n.oid=t.typnamespace
				WHERE n.nspname='mom_mes' AND t.typname IN (
					'mes_manual_labor_change','_mes_manual_labor_change',
					'mes_manual_labor_adoption','_mes_manual_labor_adoption'
				)
			)
			""")
	Boolean installationObjectsPresent();

	/**
	 * 只有设计归档集成到唯一累计入口并完整安装保护后才允许新能力。
	 */
	@Select("SELECT mom_mes.mom_manual_labor_change_ready_v1()")
	Integer ready();

	@Select("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(CONCAT('MES_MANUAL_LABOR_REQUEST:', #{tenant}, ':', #{factory}, ':', #{request}), 0))")
	Integer lockRequest(@Param("tenant") Long tenant, @Param("factory") String factory,
	                    @Param("request") String request);

	/**
	 * 原号取消可核对逻辑删除的历史原对象，不要求原命令仍有未接收资格。
	 */
	@Select("SELECT * FROM mom_mes.mes_produce_task WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND id=#{id}")
	@Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
	MesProduceTaskEntity processAny(@Param("tenant") Long tenant, @Param("factory") String factory,
	                                @Param("id") Long id);

	@Select("SELECT * FROM mom_mes.mes_task WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND code=#{code}")
	@Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
	MesTaskEntity taskAny(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("code") String code);

	@Select("SELECT * FROM mom_mes.mes_manual_labor_change WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND request_id=#{request}")
	@Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
	MesManualLaborChangeEntity request(@Param("tenant") Long tenant, @Param("factory") String factory,
	                                   @Param("request") String request);

	@Select("SELECT * FROM mom_mes.mes_manual_labor_change WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND id=#{id}")
	@Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
	MesManualLaborChangeEntity change(@Param("tenant") Long tenant, @Param("factory") String factory,
	                                  @Param("id") Long id);

	@Select("SELECT * FROM mom_mes.mes_manual_labor_change WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND process_id=#{process} AND status='APPROVED'")
	@Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
	MesManualLaborChangeEntity approved(@Param("tenant") Long tenant, @Param("factory") String factory,
	                                    @Param("process") Long process);

	/**
	 * 先检查完整历史规模，再取正文，避免先加载超量大包后才拒绝。
	 */
	@Select("SELECT coalesce(sum(octet_length(bundle_snapshot)),0) FROM mom_mes.mes_manual_labor_change WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND task_code=#{task}")
	long bundleBytes(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("task") String task);

	@Select("SELECT count(*) FROM mom_mes.mes_manual_labor_change WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND task_code=#{task}")
	long changeCount(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("task") String task);

	@Select("SELECT * FROM mom_mes.mes_manual_labor_change WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND task_code=#{task} ORDER BY proposed_at DESC,id DESC LIMIT 101")
	List<MesManualLaborChangeEntity> changes(@Param("tenant") Long tenant, @Param("factory") String factory,
	                                         @Param("task") String task);

	@Select("SELECT * FROM mom_mes.mes_manual_labor_adoption WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND process_id=#{process} ORDER BY id LIMIT 201")
	@Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
	List<MesManualLaborAdoptionEntity> adoptions(@Param("tenant") Long tenant, @Param("factory") String factory,
	                                             @Param("process") Long process);

	/**
	 * 包含逻辑删除的原派工；历史事实不能通过删除标记绕过首次接收资格。
	 */
	@Select("SELECT * FROM mom_mes.mes_task_arrange WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND produce_task_code=#{process} ORDER BY id LIMIT 201")
	@Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
	List<MesTaskArrangeEntity> arranges(@Param("tenant") Long tenant, @Param("factory") String factory,
	                                    @Param("process") String process);

	/**
	 * 未接收也允许预投料，故审批须读取含逻辑删除的本节点全部投料历史，缺工序码时仍按原派工归属。
	 */
	@Select("SELECT count(*) FROM mom_mes.mes_warehouse_feed f WHERE f.tenant_id=#{tenant} AND f.factory_code=#{factory} AND (f.produce_task_code=#{process} OR EXISTS(SELECT 1 FROM mom_mes.mes_task_arrange a WHERE a.tenant_id=#{tenant} AND a.factory_code=#{factory} AND a.produce_task_code=#{process} AND a.code=f.arrange_code))")
	long feeds(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("process") String process);

	/**
	 * 本层不改原包；早序对整单原工程的引用保持，目标节点自身实物来源占用仍阻断。
	 */
	@Select("SELECT count(*) FROM mom_mes.mes_physical_transform_source_claim WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND status='HELD' AND identity_type='PRODUCE_TASK_CODE' AND identity_key=#{process}")
	long heldNode(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("process") String process);

	@Select("SELECT count(*) FROM mom_mes.mes_aps_batch_allocation WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND mes_task_id=#{task}")
	long apsLinks(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("task") Long task);

	@Update("UPDATE mom_mes.mes_manual_labor_change SET status=#{status},reviewer_id=#{reviewerId},review_reason=#{reviewReason},reviewed_at=#{reviewedAt} WHERE id=#{id} AND tenant_id=#{tenantId} AND factory_code=#{factoryCode} AND status='PENDING'")
	int review(MesManualLaborChangeEntity row);

	@Insert("""
			INSERT INTO mom_mes.mes_manual_labor_change (
			    id,tenant_id,factory_code,request_id,
			    command_fingerprint,command_snapshot,task_code,task_id,
			    process_id,route_node_code,applicant_id,status,
			    source_fingerprint,source_snapshot,original_engineering_hash,release_id,
			    content_hash,bundle_snapshot,labor_snapshot,reason,
			    proposed_at,reviewer_id,review_reason,reviewed_at
			) VALUES (
			    #{id},#{tenantId},#{factoryCode},#{requestId},
			    #{commandFingerprint},#{commandSnapshot},#{taskCode},#{taskId},
			    #{processId},#{routeNodeCode},#{applicantId},#{status},
			    #{sourceFingerprint},#{sourceSnapshot},#{originalEngineeringHash},#{releaseId},
			    #{contentHash},#{bundleSnapshot},#{laborSnapshot},#{reason},
			    #{proposedAt},#{reviewerId},#{reviewReason},#{reviewedAt}
			)
			""")
	int insert(MesManualLaborChangeEntity row);

	@Insert("""
			INSERT INTO mom_mes.mes_manual_labor_adoption (
			    id,tenant_id,factory_code,task_code,
			    task_id,process_id,route_node_code,arrange_id,
			    change_id,original_engineering_hash,release_id,content_hash,
			    labor_snapshot,source_fingerprint,received_at
			) VALUES (
			    #{id},#{tenantId},#{factoryCode},#{taskCode},
			    #{taskId},#{processId},#{routeNodeCode},#{arrangeId},
			    #{changeId},#{originalEngineeringHash},#{releaseId},#{contentHash},
			    #{laborSnapshot},#{sourceFingerprint},#{receivedAt}
			)
			""")
	int adopt(MesManualLaborAdoptionEntity row);
}
