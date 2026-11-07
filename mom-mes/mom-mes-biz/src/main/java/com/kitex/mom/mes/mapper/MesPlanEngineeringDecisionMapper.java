package com.kitex.mom.mes.mapper;

import com.kitex.mom.mes.entity.MesPlanEngineeringDecisionEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * MES普通PLAN工单节点工时采用/取消永久决策；禁止通用CRUD，组织、幂等与终态均由数据库守卫复核。
 */
@Mapper
public interface MesPlanEngineeringDecisionMapper {

	/**
	 * 只探测正式ready签名；不存在时不执行缺失函数。
	 */
	@Select("""
			SELECT to_regprocedure('mom_mes.mom_plan_engineering_decision_ready_v1()') IS NOT NULL
			""")
	@Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
	Boolean readyPresent();

	/**
	 * 函数、关系、类型及触发器任一残留均表示需要核安装。
	 */
	@Select("""
			SELECT EXISTS (
			    SELECT 1 FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_namespace n ON n.oid=p.pronamespace
			    WHERE n.nspname='mom_mes' AND (p.proname LIKE 'mom_plan_engineering_%' OR p.proname LIKE 'mom_plan_labor_%')
			    UNION ALL
			    SELECT 1 FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
			    WHERE n.nspname='mom_mes' AND (c.relname LIKE 'mes_plan_engineering_decision%'
			        OR c.relname LIKE 'mes_plan_labor_adoption%')
			    UNION ALL
			    SELECT 1 FROM pg_catalog.pg_type t JOIN pg_catalog.pg_namespace n ON n.oid=t.typnamespace
			    WHERE n.nspname='mom_mes' AND (ltrim(t.typname,'_') LIKE 'mes_plan_engineering_decision%'
			        OR ltrim(t.typname,'_') LIKE 'mes_plan_labor_adoption%')
			    UNION ALL
			    SELECT 1 FROM pg_catalog.pg_trigger g JOIN pg_catalog.pg_class c ON c.oid=g.tgrelid
			    JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
			    WHERE n.nspname='mom_mes' AND g.tgname LIKE 'trg_plan_labor_%'
			)
			""")
	@Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
	Boolean installationObjectsPresent();

	/**
	 * 仅1表示新账与来源/接收保护完整就绪。
	 */
	@Select("""
			SELECT mom_mes.mom_plan_engineering_decision_ready_v1()
			""")
	@Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
	Integer ready();

	/**
	 * 采用与取消同组织同PEP使用同一事务锁，必须在RC本地事务调用。
	 */
	@Select("""
			SELECT mom_mes.mom_plan_engineering_lock_request_v1(#{tenant},#{factory},#{request})
			""")
	@Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
	Integer lockRequest(@Param("tenant") Long tenant, @Param("factory") String factory,
	                    @Param("request") String request);

	/**
	 * 精确读取原号实际永久终态，锁后清除会话缓存。
	 */
	@Select("""
			SELECT * FROM mom_mes.mes_plan_engineering_decision
			WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND request_id=#{request}
			""")
	@Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
	MesPlanEngineeringDecisionEntity request(@Param("tenant") Long tenant,
	                                         @Param("factory") String factory,
	                                         @Param("request") String request);

	/**
	 * 仅实际APPLIED层可成为本稳定节点后续执行来源。
	 */
	@Select("""
			SELECT * FROM mom_mes.mes_plan_engineering_decision
			WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND process_id=#{process} AND decision='APPLIED'
			""")
	@Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
	MesPlanEngineeringDecisionEntity applied(@Param("tenant") Long tenant,
	                                         @Param("factory") String factory,
	                                         @Param("process") Long process);

	/**
	 * 最多201条实际APPLIED轻量身份索引；取消历史不进入节点标准视图，不提前加载完整工程原件。
	 */
	@Select("""
			SELECT id,tenant_id,factory_code,task_id,task_code,preparation_id,request_id,process_id,route_node_code,decision
			FROM mom_mes.mes_plan_engineering_decision
			WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND task_code=#{task} AND decision='APPLIED'
			ORDER BY decided_at DESC,id DESC LIMIT 201
			""")
	@Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
	List<MesPlanEngineeringDecisionEntity> byTask(@Param("tenant") Long tenant,
	                                              @Param("factory") String factory,
	                                              @Param("task") String task);

	/**
	 * 历史接收只核真实任务主键的新层是否存在，不枚举取消历史或全部节点，不受其数量影响。
	 */
	@Select("""
			SELECT EXISTS (SELECT 1 FROM mom_mes.mes_plan_engineering_decision d
			    WHERE d.tenant_id=#{tenant} AND d.factory_code=#{factory} AND d.task_id=#{taskId} AND d.decision='APPLIED')
			OR EXISTS (SELECT 1 FROM mom_mes.mes_plan_labor_adoption a
			    WHERE a.tenant_id=#{tenant} AND a.factory_code=#{factory} AND a.task_id=#{taskId})
			""")
	@Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
	boolean hasTaskLayer(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("taskId") Long taskId);

	/**
	 * 全工单标准视图先核实际APPLIED证据总字节，超限拒绝而非截断或先加载大包。
	 */
	@Select("""
			SELECT coalesce(sum(coalesce(octet_length(bundle_snapshot),0)
			    +coalesce(octet_length(source_snapshot),0)+coalesce(octet_length(labor_snapshot),0)
			    +coalesce(octet_length(authority_snapshot),0)+coalesce(octet_length(command_snapshot),0)
			    +coalesce(octet_length(receipt_snapshot),0)),0)
			FROM mom_mes.mes_plan_engineering_decision
			WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND task_code=#{task} AND decision='APPLIED'
			""")
	@Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
	long evidenceBytes(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("task") String task);

	/**
	 * 显式写全原件和公共审计；调用方必须核影响行数并在本地事务提交后返回。
	 */
	@Insert("""
			INSERT INTO mom_mes.mes_plan_engineering_decision (
			    id,tenant_id,factory_code,preparation_id,
			    request_id,authority_fingerprint,authority_snapshot,task_id,
			    task_code,product_code,source_release_id,source_content_hash,
			    target_release_id,target_content_hash,decision,actor_id,
			    decided_at,reason,command_snapshot,command_fingerprint,
			    receipt_snapshot,receipt_fingerprint,process_id,route_node_code,
			    process_code,source_snapshot,source_fingerprint,bundle_snapshot,
			    labor_snapshot,create_by,create_time,update_by,
			    update_time,del_flag
			) VALUES (
			    #{row.id},#{row.tenantId},#{row.factoryCode},#{row.preparationId},
			    #{row.requestId},#{row.authorityFingerprint},#{row.authoritySnapshot},#{row.taskId},
			    #{row.taskCode},#{row.productCode},#{row.sourceReleaseId},#{row.sourceContentHash},
			    #{row.targetReleaseId},#{row.targetContentHash},#{row.decision},#{row.actorId},
			    #{row.decidedAt},#{row.reason},#{row.commandSnapshot},#{row.commandFingerprint},
			    #{row.receiptSnapshot},#{row.receiptFingerprint},#{row.processId},#{row.routeNodeCode},
			    #{row.processCode},#{row.sourceSnapshot},#{row.sourceFingerprint},#{row.bundleSnapshot},
			    #{row.laborSnapshot},#{row.createBy},#{row.createTime},#{row.updateBy},
			    #{row.updateTime},#{row.delFlag}
			)
			""")
	int insert(@Param("row") MesPlanEngineeringDecisionEntity row);
}
