package com.kitex.mom.plan.mapper;

import com.kitex.mom.plan.entity.PlnEngineeringResolutionEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * PLAN不可变工程终结索引专用持久化入口；不暴露通用CRUD，全部操作限定实际组织。
 */
@Mapper
public interface PlnEngineeringResolutionMapper {

	/**
	 * 只探测V2签名，不直接解析尚未安装函数。
	 */
	@Select("""
			SELECT to_regprocedure('mom_plan.mom_engineering_preparation_ready_v2()') IS NOT NULL
			""")
	@Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
	Boolean readyPresent();

	/**
	 * 探测新增对象的任一残留；部分安装不得回落旧协议。
	 */
	@Select("""
			SELECT EXISTS (
			    SELECT 1 FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_namespace n ON n.oid=p.pronamespace
			    WHERE n.nspname='mom_plan' AND (p.proname='mom_engineering_preparation_ready_v2'
			        OR p.proname LIKE 'mom_plan_labor_%')
			    UNION ALL
			    SELECT 1 FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
			    WHERE n.nspname='mom_plan' AND c.relname LIKE 'pln_engineering_resolution%'
			    UNION ALL
			    SELECT 1 FROM pg_catalog.pg_type t JOIN pg_catalog.pg_namespace n ON n.oid=t.typnamespace
			    WHERE n.nspname='mom_plan' AND ltrim(t.typname,'_') LIKE 'pln_engineering_resolution%'
			    UNION ALL
			    SELECT 1 FROM pg_catalog.pg_trigger g JOIN pg_catalog.pg_class c ON c.oid=g.tgrelid
			    JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
			    WHERE n.nspname='mom_plan' AND g.tgname LIKE 'trg_plan_labor_%'
			)
			""")
	@Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
	Boolean installationObjectsPresent();

	/**
	 * 仅1证明V2全部不可变账、原件与来源守卫完整。
	 */
	@Select("""
			SELECT mom_plan.mom_engineering_preparation_ready_v2()
			""")
	@Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
	Integer ready();

	/**
	 * 原准备同组织唯一终态，不以业务编码猜主键。
	 */
	@Select("""
			SELECT * FROM mom_plan.pln_engineering_resolution
			WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND preparation_id=#{preparation}
			""")
	@Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
	PlnEngineeringResolutionEntity byPreparation(@Param("tenant") Long tenant,
	                                             @Param("factory") String factory,
	                                             @Param("preparation") Long preparation);

	/**
	 * 批量精确加载本次准备父账的全部终结索引，传入集合须非空。
	 */
	@Select("""
			<script>
			SELECT * FROM mom_plan.pln_engineering_resolution WHERE tenant_id=#{tenant} AND factory_code=#{factory}
			AND preparation_id IN
			<foreach collection="parents" item="id" open="(" separator="," close=")">#{id}</foreach>
			ORDER BY preparation_id
			</script>
			""")
	@Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
	List<PlnEngineeringResolutionEntity> forParents(@Param("tenant") Long tenant,
	                                                @Param("factory") String factory,
	                                                @Param("parents") List<Long> parents);

	/**
	 * 显式写全原件和公共审计；调用方必须核影响行数并在本地事务提交后返回。
	 */
	@Insert("""
			INSERT INTO mom_plan.pln_engineering_resolution (
			    id,tenant_id,factory_code,preparation_id,
			    request_id,authority_fingerprint,decision,mes_decision_id,
			    mes_decision_fingerprint,mes_receipt_snapshot,actor_id,decided_at,
			    reason,create_by,create_time,update_by,
			    update_time,del_flag
			) VALUES (
			    #{row.id},#{row.tenantId},#{row.factoryCode},#{row.preparationId},
			    #{row.requestId},#{row.authorityFingerprint},#{row.decision},#{row.mesDecisionId},
			    #{row.mesDecisionFingerprint},#{row.mesReceiptSnapshot},#{row.actorId},#{row.decidedAt},
			    #{row.reason},#{row.createBy},#{row.createTime},#{row.updateBy},
			    #{row.updateTime},#{row.delFlag}
			)
			""")
	int insert(@Param("row") PlnEngineeringResolutionEntity row);
}
