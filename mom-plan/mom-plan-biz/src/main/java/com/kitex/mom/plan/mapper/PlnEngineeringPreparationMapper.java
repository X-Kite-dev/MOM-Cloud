package com.kitex.mom.plan.mapper;

import com.kitex.mom.plan.dto.PlnEngineeringPreparationDTO;
import com.kitex.mom.plan.entity.PlnEngineeringPreparationEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * PLAN工程准备的专用持久化入口。显式限定实际租户/工厂，保留取消终态及原始证据，
 * 不暴露通用CRUD。数据库守卫与领域服务使用相同原号锁和来源锁；调用方必须使用READ COMMITTED。
 */
@Mapper
public interface PlnEngineeringPreparationMapper {

	/**
	 * 只读目录探测正式ready签名；不存在时不得直接调用缺失函数。
	 */
	@Select("SELECT to_regprocedure('mom_plan.mom_engineering_preparation_ready_v1()') IS NOT NULL")
	@Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
	Boolean readyPresent();

	/**
	 * 探测任一安装残留，覆盖函数、表、复合类型、索引及原表新增触发器。
	 * 部分安装不得按完全未安装继续原写流程；此查询不依赖任何新增对象。
	 */
	@Select("""
			SELECT EXISTS (
			    SELECT 1 FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_namespace n ON n.oid=p.pronamespace
			    WHERE n.nspname='mom_plan' AND (p.proname LIKE 'mom_engineering_%'
			        OR p.proname LIKE 'mom_guard_engineering_%' OR p.proname LIKE 'mom_check_engineering_%')
			    UNION ALL
			    SELECT 1 FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
			    WHERE n.nspname='mom_plan' AND c.relname LIKE 'pln_engineering_preparation%'
			    UNION ALL
			    SELECT 1 FROM pg_catalog.pg_type t JOIN pg_catalog.pg_namespace n ON n.oid=t.typnamespace
			    WHERE n.nspname='mom_plan' AND ltrim(t.typname,'_') LIKE 'pln_engineering_preparation%'
			    UNION ALL
			    SELECT 1 FROM pg_catalog.pg_trigger t JOIN pg_catalog.pg_class c ON c.oid=t.tgrelid
			    JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
			    WHERE n.nspname='mom_plan' AND t.tgname LIKE 'trg_plan_engineering_%'
			)
			""")
	@Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
	Boolean installationObjectsPresent();

	/**
	 * 核精确函数原文、目录结构及ALWAYS守卫；仅1表示本版完整就绪。
	 */
	@Select("SELECT mom_plan.mom_engineering_preparation_ready_v1()")
	@Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
	Integer ready();

	/**
	 * 同组织原请求永久串行锁；准备与原号取消使用相同键。
	 */
	@Select("SELECT mom_plan.mom_engineering_lock_request_v1(#{tenant},#{factory},#{request})")
	@Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
	Integer lockRequest(@Param("tenant") Long tenant, @Param("factory") String factory,
	                    @Param("request") String request);

	/**
	 * 单来源事务锁；多来源调用方应使用按完整键排序的批量入口以避免锁序分叉。
	 */
	@Select("SELECT mom_plan.mom_engineering_lock_source_v1(#{tenant},#{factory},#{kind},#{sourceId})")
	@Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
	Integer lockSource(@Param("tenant") Long tenant, @Param("factory") String factory,
	                   @Param("kind") String kind, @Param("sourceId") Long sourceId);

	/**
	 * 一次往返锁定全量来源；数据库函数按完整锁键C排序逐一获取事务锁。
	 */
	@Select("""
			<script>
			SELECT mom_plan.mom_engineering_lock_sources_v1(#{tenant},#{factory},
			    (SELECT jsonb_agg(jsonb_build_object('kind',s.kind,'sourceId',s.source_id))
			     FROM (VALUES
			     <foreach collection="sources" item="s" separator=",">
			         (#{s.kind}::text,#{s.sourceId}::bigint)
			     </foreach>
			     ) AS s(kind,source_id)))
			</script>
			""")
	@Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
	Integer lockSources(@Param("tenant") Long tenant, @Param("factory") String factory,
	                    @Param("sources") List<PlnEngineeringPreparationDTO.SourceScope> sources);

	/**
	 * 原号回读包含终态；清除会话缓存以免锁后读到锁前结果。
	 */
	@Select("""
			SELECT
			id, tenant_id, factory_code, request_id, request_fingerprint, canonical_request, task_code, task_id,
			source_release_id, source_content_hash, target_release_id, target_content_hash, observation_fingerprint,
			observation_snapshot, observation_snapshot_hash, reason, status, applicant_id, prepared_at, cancelled_by,
			cancelled_at, cancel_reason, create_by, create_time, update_by, update_time, del_flag
			FROM mom_plan.pln_engineering_preparation
			WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND request_id=#{request}
			""")
	@Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
	PlnEngineeringPreparationEntity request(@Param("tenant") Long tenant, @Param("factory") String factory,
	                                        @Param("request") String request);

	/**
	 * 最近记录最多51条供服务拒绝超限；省略完整观察和规范命令正文。
	 */
	@Select("""
			SELECT
			id, tenant_id, factory_code, request_id, request_fingerprint, task_code, task_id, source_release_id,
			source_content_hash, target_release_id, target_content_hash, observation_fingerprint,
			observation_snapshot_hash, reason, status, applicant_id, prepared_at, cancelled_by, cancelled_at,
			cancel_reason, create_by, create_time, update_by, update_time, del_flag
			FROM mom_plan.pln_engineering_preparation
			WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND task_code=#{task}
			ORDER BY create_time DESC,id DESC LIMIT 51
			""")
	@Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
	List<PlnEngineeringPreparationEntity> recent(@Param("tenant") Long tenant, @Param("factory") String factory,
	                                             @Param("task") String task);

	/**
	 * 显式写入原命令、原观察和审计；created_xid由数据库生成并限制来源只能同事务首次插入。
	 * 调用方必须核影响行数为1，并在同事务写全来源范围。
	 */
	@Insert("""
			INSERT INTO mom_plan.pln_engineering_preparation (
			id, tenant_id, factory_code, request_id, request_fingerprint, canonical_request, task_code, task_id,
			source_release_id, source_content_hash, target_release_id, target_content_hash, observation_fingerprint,
			observation_snapshot, observation_snapshot_hash, reason, status, applicant_id, prepared_at, cancelled_by,
			cancelled_at, cancel_reason, create_by, create_time, update_by, update_time, del_flag
			) VALUES (
			#{row.id}, #{row.tenantId}, #{row.factoryCode}, #{row.requestId}, #{row.requestFingerprint},
			#{row.canonicalRequest}, #{row.taskCode}, #{row.taskId}, #{row.sourceReleaseId}, #{row.sourceContentHash},
			#{row.targetReleaseId}, #{row.targetContentHash}, #{row.observationFingerprint},
			#{row.observationSnapshot}, #{row.observationSnapshotHash}, #{row.reason}, #{row.status},
			#{row.applicantId}, #{row.preparedAt}, #{row.cancelledBy}, #{row.cancelledAt}, #{row.cancelReason},
			#{row.createBy}, #{row.createTime}, #{row.updateBy}, #{row.updateTime}, #{row.delFlag}
			)
			""")
	int insert(@Param("row") PlnEngineeringPreparationEntity row);

	/**
	 * 插入同事务创建父账的单个不可变来源，禁止后补及取消后追加。
	 */
	@Insert("""
			INSERT INTO mom_plan.pln_engineering_preparation_scope
			    (id,parent_id,tenant_id,factory_code,source_kind,source_id,
			     create_by,update_by,create_time,update_time,del_flag)
			VALUES (#{id},#{parent},#{tenant},#{factory},#{kind},#{sourceId},
			    #{actor},#{actor},localtimestamp,localtimestamp,'0')
			""")
	int addScope(@Param("id") Long id, @Param("parent") Long parent, @Param("tenant") Long tenant,
	             @Param("factory") String factory, @Param("kind") String kind, @Param("sourceId") Long sourceId,
	             @Param("actor") String actor);

	/**
	 * 批量保存完整来源；公共参数只绑定一次，15000个来源仍低于PG参数数量上限。
	 */
	@Insert("""
			<script>
			WITH args AS (SELECT #{parent}::bigint parent_id,#{tenant}::bigint tenant_id,
			    #{factory}::varchar factory_code,#{actor}::varchar actor),
			sources(id,source_kind,source_id) AS (VALUES
			<foreach collection="sources" item="s" separator=",">
			    (#{s.id}::bigint,#{s.kind}::varchar,#{s.sourceId}::bigint)
			</foreach>
			)
			INSERT INTO mom_plan.pln_engineering_preparation_scope
			    (id,parent_id,tenant_id,factory_code,source_kind,source_id,
			     create_by,update_by,create_time,update_time,del_flag)
			SELECT s.id,a.parent_id,a.tenant_id,a.factory_code,s.source_kind,s.source_id,
			    a.actor,a.actor,localtimestamp,localtimestamp,'0'
			FROM sources s CROSS JOIN args a
			</script>
			""")
	int addScopes(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("parent") Long parent,
	              @Param("sources") List<PlnEngineeringPreparationDTO.SourceScopeWithId> sources,
	              @Param("actor") String actor);

	/**
	 * 已锁定来源的有效准备冲突数；取消记录保留范围但不继续占用。
	 */
	@Select("""
			SELECT count(*) FROM mom_plan.pln_engineering_preparation_scope s
			JOIN mom_plan.pln_engineering_preparation p
			ON p.id=s.parent_id AND p.tenant_id=s.tenant_id AND p.factory_code=s.factory_code
			WHERE s.tenant_id=#{tenant} AND s.factory_code=#{factory}
			AND s.source_kind=#{kind} AND s.source_id=#{sourceId} AND p.status='PREPARED'
			AND NOT EXISTS (SELECT 1 FROM mom_plan.pln_engineering_resolution r
			    WHERE r.preparation_id=p.id AND r.tenant_id=p.tenant_id AND r.factory_code=p.factory_code)
			""")
	@Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
	int activeConflicts(@Param("tenant") Long tenant, @Param("factory") String factory,
	                    @Param("kind") String kind, @Param("sourceId") Long sourceId);

	/**
	 * 一次查询找任一冲突原号，不读取大原文；必须先获得全部来源锁。
	 */
	@Select("""
			<script>
			WITH requested(source_kind,source_id) AS (VALUES
			<foreach collection="sources" item="s" separator=",">
			    (#{s.kind}::varchar,#{s.sourceId}::bigint)
			</foreach>
			)
			SELECT p.request_id FROM requested r
			JOIN mom_plan.pln_engineering_preparation_scope s
			    ON s.source_kind=r.source_kind AND s.source_id=r.source_id
			JOIN mom_plan.pln_engineering_preparation p
			    ON p.id=s.parent_id AND p.tenant_id=s.tenant_id AND p.factory_code=s.factory_code
			WHERE s.tenant_id=#{tenant} AND s.factory_code=#{factory} AND p.status='PREPARED'
			AND NOT EXISTS (SELECT 1 FROM mom_plan.pln_engineering_resolution e
			    WHERE e.preparation_id=p.id AND e.tenant_id=p.tenant_id AND e.factory_code=p.factory_code)
			ORDER BY p.request_id COLLATE "C" LIMIT 1
			</script>
			""")
	@Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
	String firstActiveRequest(@Param("tenant") Long tenant, @Param("factory") String factory,
	                          @Param("sources") List<PlnEngineeringPreparationDTO.SourceScope> sources);

	/**
	 * 同组织父账全量不可变来源数量；回读用于核实原受理证据。
	 */
	@Select("""
			SELECT count(*) FROM mom_plan.pln_engineering_preparation_scope
			WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND parent_id=#{parent}
			""")
	@Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
	int countScopes(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("parent") Long parent);

	/**
	 * 仅首次PREPARED到CANCELLED迁移；零行必须由领域层按原号回读辨别终态。
	 */
	@Update("""
			UPDATE mom_plan.pln_engineering_preparation
			SET status='CANCELLED',cancelled_by=#{actor},cancelled_at=#{at},cancel_reason=#{reason},
			    update_by=#{actorName},update_time=#{at}
			WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND id=#{id} AND status='PREPARED'
			""")
	int cancel(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("id") Long id,
	           @Param("actor") Long actor, @Param("actorName") String actorName,
	           @Param("reason") String reason, @Param("at") LocalDateTime at);
}
