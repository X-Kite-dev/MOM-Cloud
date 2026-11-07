package com.kitex.mom.mes.mapper;

import com.kitex.mom.mes.entity.MesPlanLaborAdoptionEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * MES普通PLAN派工接收时标准工时采用的专用不可变账，须在原receive事务使用。
 */
@Mapper
public interface MesPlanLaborAdoptionMapper {

	/**
	 * 原稳定节点完整接收来源，最多201条供领域层拒绝超限。
	 */
	@Select("""
			SELECT * FROM mom_mes.mes_plan_labor_adoption
			WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND process_id=#{process} ORDER BY id LIMIT 201
			""")
	@Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
	List<MesPlanLaborAdoptionEntity> byProcess(@Param("tenant") Long tenant,
	                                           @Param("factory") String factory,
	                                           @Param("process") Long process);

	/**
	 * 原派工同组织唯一实际接收冻结，历史不可重写。
	 */
	@Select("""
			SELECT * FROM mom_mes.mes_plan_labor_adoption
			WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND arrange_id=#{arrange}
			""")
	@Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
	MesPlanLaborAdoptionEntity byArrange(@Param("tenant") Long tenant,
	                                     @Param("factory") String factory,
	                                     @Param("arrange") Long arrange);

	/**
	 * 显式写全原件和公共审计；调用方必须核影响行数并在本地事务提交后返回。
	 */
	@Insert("""
			INSERT INTO mom_mes.mes_plan_labor_adoption (
			    id,tenant_id,factory_code,task_code,
			    task_id,process_id,route_node_code,arrange_id,
			    decision_id,original_engineering_hash,release_id,content_hash,
			    labor_snapshot,source_fingerprint,received_at,create_by,
			    create_time,update_by,update_time,del_flag
			) VALUES (
			    #{row.id},#{row.tenantId},#{row.factoryCode},#{row.taskCode},
			    #{row.taskId},#{row.processId},#{row.routeNodeCode},#{row.arrangeId},
			    #{row.decisionId},#{row.originalEngineeringHash},#{row.releaseId},#{row.contentHash},
			    #{row.laborSnapshot},#{row.sourceFingerprint},#{row.receivedAt},#{row.createBy},
			    #{row.createTime},#{row.updateBy},#{row.updateTime},#{row.delFlag}
			)
			""")
	int insert(@Param("row") MesPlanLaborAdoptionEntity row);
}
