package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.kitex.mom.mes.entity.MesTaskEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 父工单详情与管理命令的真实归属、锁行及草稿关联事实查询，不提供通用事实修改入口。
 * 显式按租户与实际工厂限定，历史逻辑删除的关联事实也不能当作从未存在。
 */
@Mapper
public interface MesTaskCommandMapper {

	/**
	 * 无锁观察准确父单，供取得真实业务根锁；不是写许可。
	 */
	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Select("""
			SELECT * FROM mom_mes.mes_task
			WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND id=#{id} AND del_flag='0'
			""")
	@Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
	MesTaskEntity observe(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("id") Long id);

	/**
	 * 在已核验读取权限的实际组织内按原始编码观察父单，最多取两条供调用方拒绝重复码。
	 * 不修剪编码、不跨组织兜底；显式组织条件替代通用拦截器，不授予写入许可。
	 *
	 * @param tenant  当前实名身份所属租户，不允许默认租户
	 * @param factory 已通过read授权核验的实际工厂
	 * @param code    非空白的准确原始工单编码
	 * @return 最多两条未删除父单；空集合表示当前实际组织未找到
	 */
	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Select("""
			SELECT * FROM mom_mes.mes_task
			WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND code=#{code} AND del_flag='0'
			LIMIT 2
			""")
	@Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
	List<MesTaskEntity> observeByCode(@Param("tenant") Long tenant, @Param("factory") String factory,
	                                  @Param("code") String code);

	/**
	 * 取得真实根锁及修订准入后才允许调用；独立SQL避免复用此前观察缓存。
	 */
	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Select("""
			SELECT * FROM mom_mes.mes_task
			WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND id=#{id} AND del_flag='0'
			FOR UPDATE
			""")
	@Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
	MesTaskEntity lock(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("id") Long id);

	/**
	 * CREATE状态若已有子任务或派工，即使逻辑删除也不是未下达草稿，不可重写或重新下达。
	 */
	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Select("""
			SELECT '工序任务' WHERE EXISTS (
			    SELECT 1 FROM mom_mes.mes_produce_task
			    WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND task_code=#{code}
			)
			UNION ALL
			SELECT '派工记录' WHERE EXISTS (
			    SELECT 1 FROM mom_mes.mes_task_arrange
			    WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND task_code=#{code}
			)
			""")
	@Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
	List<String> childFacts(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("code") String code);

	/**
	 * 返回已存在的关联事实类别。任何一类、结构缺失或查询失败都不能证明草稿可独立作废。
	 * 查询含原已删除历史；不跨域核销库存、质量或计划，不改变原建单永久回执。
	 */
	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Select("""
			SELECT '工序任务' WHERE EXISTS (
			    SELECT 1 FROM mom_mes.mes_produce_task
			    WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND task_code=#{code}
			)
			UNION ALL
			SELECT '派工记录' WHERE EXISTS (
			    SELECT 1 FROM mom_mes.mes_task_arrange
			    WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND task_code=#{code}
			)
			UNION ALL
			SELECT '报工记录' WHERE EXISTS (
			    SELECT 1 FROM mom_mes.mes_task_report
			    WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND task_code=#{code}
			)
			UNION ALL
			SELECT '执行工时' WHERE EXISTS (
			    SELECT 1 FROM mom_mes.mes_task_execution_segment
			    WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND task_code=#{code}
			)
			UNION ALL
			SELECT '工件计时' WHERE EXISTS (
			    SELECT 1 FROM mom_mes.mes_workpiece_time_session
			    WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND task_code=#{code}
			)
			UNION ALL
			SELECT '领料记录' WHERE EXISTS (
			    SELECT 1 FROM mom_mes.mes_warehouse_picking_bill
			    WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND task_code=#{code}
			)
			UNION ALL
			SELECT '投料记录' WHERE EXISTS (
			    SELECT 1 FROM mom_mes.mes_warehouse_feed
			    WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND task_code=#{code}
			)
			UNION ALL
			SELECT '工装占用' WHERE EXISTS (
			    SELECT 1 FROM mom_mes.mes_tooling_execution
			    WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND task_code=#{code}
			)
			UNION ALL
			SELECT '质检生成记录' WHERE EXISTS (
			    SELECT 1 FROM mom_mes.mes_inspection_gen_record
			    WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND task_code=#{code}
			)
			UNION ALL
			SELECT '实际批次绑定' WHERE EXISTS (
			    SELECT 1 FROM mom_mes.mes_start_material_binding
			    WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND task_code=#{code}
			)
			UNION ALL
			SELECT '手工工时变更' WHERE EXISTS (
			    SELECT 1 FROM mom_mes.mes_manual_labor_change
			    WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND task_code=#{code}
			)
			UNION ALL
			SELECT '手工工时采用' WHERE EXISTS (
			    SELECT 1 FROM mom_mes.mes_manual_labor_adoption
			    WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND task_code=#{code}
			)
			UNION ALL
			SELECT '计划工程决策' WHERE EXISTS (
			    SELECT 1 FROM mom_mes.mes_plan_engineering_decision
			    WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND task_code=#{code}
			)
			UNION ALL
			SELECT '计划工时采用' WHERE EXISTS (
			    SELECT 1 FROM mom_mes.mes_plan_labor_adoption
			    WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND task_code=#{code}
			)
			UNION ALL
			SELECT 'APS来源分配' WHERE EXISTS (
			    SELECT 1 FROM mom_mes.mes_aps_batch_allocation
			    WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND mes_task_id=#{id}
			)
			UNION ALL
			SELECT '质量来源数量' WHERE EXISTS (
			    SELECT 1 FROM mom_mes.mes_aps_source_quantity_event
			    WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND mes_task_id=#{id}
			)
			UNION ALL
			SELECT '入库请求' WHERE EXISTS (
			    SELECT 1 FROM mom_mes.mes_task_in_wms_receipt
			    WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND mes_task_id=#{id}
			)
			UNION ALL
			SELECT '制造单件身份' WHERE EXISTS (
			    SELECT 1 FROM mom_mes.mes_manufacturing_piece
			    WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND task_id=#{id}
			)
			""")
	@Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
	List<String> draftFacts(@Param("tenant") Long tenant, @Param("factory") String factory,
	                        @Param("id") Long id, @Param("code") String code);
}
