package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.kitex.mom.mes.entity.MesPackagingSealEntity;
import com.kitex.mom.mes.entity.MesWrapRecordEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 标准包装结构、原号互斥及真实来源占用查询；所有查询显式限定实际组织。 */
@Mapper
public interface MesPackagingSealMapper extends BaseMapper<MesPackagingSealEntity> {

	/** @return 数据库完整守卫就绪为1，未安装、禁用或不兼容为0。 */
	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Select("SELECT mom_mes.mes_packaging_v1_ready()")
	Integer ready();

	/** 同租户原封箱号先取互斥；已有永久结果可在生产许可/修订冻结之前回读。 */
	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Select("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(#{key}, 0))")
	Integer lockRequest(@Param("key") String key);

	/**
	 * 同号跨工厂或跨人不得被视为未执行；不依赖工厂过滤器或逻辑删除重开原号。
	 */
	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Select("SELECT * FROM mom_mes.mes_packaging_seal WHERE tenant_id=#{tenant} AND request_id=#{request}")
	MesPackagingSealEntity permanent(@Param("tenant") Long tenant, @Param("request") String request);

	/**
	 * 逐件自动满箱只观察最早的足量真实可封内容；锁内仍须重读报告及成员。
	 * 在SQL内过滤质量未放行和转换占用，避免每次扫码加载整条历史队列并逐行跨层查询。
	 */
	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Select("SELECT r.* FROM mom_mes.mes_wrap_record r JOIN mom_mes.mes_task_report p "
			+ "ON p.id=r.report_id AND p.tenant_id=r.tenant_id AND p.factory_code=r.factory_code "
			+ "AND p.task_code=r.task_code AND p.produce_task_code=r.procedure_task_code AND p.arrange_code=r.arrange_code "
			+ "WHERE r.tenant_id=#{tenant} AND r.factory_code=#{factory} AND r.task_code=#{task} "
			+ "AND r.procedure_task_code=#{node} AND r.arrange_code=#{arrange} AND r.del_flag='0' "
			+ "AND r.packing_status='STAGED' AND r.wrap_code IS NULL AND r.source_record_id IS NULL "
			+ "AND r.quantity=1 AND p.del_flag='0' AND p.status='EFFECTIVE' AND p.report_mode='PACKAGE_ONE_CODE' "
			+ "AND p.report_num=r.quantity AND p.effective_report_num=r.quantity "
			+ "AND p.quality_quantity_status IN ('NOT_REQUIRED','RELEASED') AND p.quality_released_num>=r.quantity "
			+ "AND NOT EXISTS(SELECT 1 FROM mom_mes.mes_physical_transform_source_claim c "
			+ "WHERE c.tenant_id=r.tenant_id AND c.factory_code=r.factory_code AND c.del_flag='0' AND ("
			+ "(c.status='HELD' AND ((c.identity_type='TASK_CODE' AND c.identity_key=r.task_code) "
			+ "OR (c.identity_type='PRODUCE_TASK_CODE' AND c.identity_key=r.procedure_task_code) "
			+ "OR (c.identity_type='ARRANGE_ID' AND c.identity_key=#{arrangeId}) "
			+ "OR (c.identity_type='ARRANGE_CODE' AND c.identity_key=r.arrange_code))) "
			+ "OR (c.status IN ('HELD','CONSUMED') AND ((c.identity_type='REPORT_ID' AND c.identity_key=p.id::text) "
			+ "OR (c.identity_type='IDENTIFIER' AND c.identity_key=r.transfer_code))))) "
			+ "ORDER BY r.create_time,r.id LIMIT #{capacity}")
	List<MesWrapRecordEntity> eligibleStaged(@Param("tenant") Long tenant, @Param("factory") String factory,
			@Param("task") String task, @Param("node") String node, @Param("arrange") String arrange,
			@Param("arrangeId") String arrangeId, @Param("capacity") Integer capacity);

	/** 当前工作区一次查询全部待装来源的转换限制，避免逐成员查询占用。 */
	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Select("SELECT DISTINCT p.id FROM mom_mes.mes_task_report p JOIN mom_mes.mes_wrap_record r "
			+ "ON r.report_id=p.id AND r.tenant_id=p.tenant_id AND r.factory_code=p.factory_code "
			+ "AND r.task_code=p.task_code AND r.procedure_task_code=p.produce_task_code AND r.arrange_code=p.arrange_code "
			+ "JOIN mom_mes.mes_physical_transform_source_claim c ON c.tenant_id=p.tenant_id AND c.factory_code=p.factory_code "
			+ "WHERE p.tenant_id=#{tenant} AND p.factory_code=#{factory} AND p.task_code=#{task} "
			+ "AND p.produce_task_code=#{node} AND p.arrange_code=#{arrange} AND p.del_flag='0' "
			+ "AND r.del_flag='0' AND r.packing_status='STAGED' AND r.wrap_code IS NULL AND c.del_flag='0' AND ("
			+ "(c.status='HELD' AND ((c.identity_type='TASK_CODE' AND c.identity_key=p.task_code) "
			+ "OR (c.identity_type='PRODUCE_TASK_CODE' AND c.identity_key=p.produce_task_code) "
			+ "OR (c.identity_type='ARRANGE_ID' AND c.identity_key=#{arrangeId}) "
			+ "OR (c.identity_type='ARRANGE_CODE' AND c.identity_key=p.arrange_code))) "
			+ "OR (c.status IN ('HELD','CONSUMED') AND ((c.identity_type='REPORT_ID' AND c.identity_key=p.id::text) "
			+ "OR (c.identity_type='IDENTIFIER' AND c.identity_key=p.transfer_code))))")
	List<Long> blockedPendingReports(@Param("tenant") Long tenant, @Param("factory") String factory,
			@Param("task") String task, @Param("node") String node, @Param("arrange") String arrange,
			@Param("arrangeId") String arrangeId);

	/**
	 * 活动来源占用阻断封箱；已经正式转换消耗的原报告/标识同样不能重新首次封箱。
	 */
	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Select("SELECT COUNT(*) FROM mom_mes.mes_physical_transform_source_claim c "
			+ "WHERE c.tenant_id=#{tenant} AND c.factory_code=#{factory} AND c.del_flag='0' AND ("
			+ "(c.status='HELD' AND ((c.identity_type='TASK_CODE' AND c.identity_key=#{task}) "
			+ "OR (c.identity_type='PRODUCE_TASK_CODE' AND c.identity_key=#{node}) "
			+ "OR (c.identity_type='ARRANGE_ID' AND c.identity_key=#{arrangeId}) "
			+ "OR (c.identity_type='ARRANGE_CODE' AND c.identity_key=#{arrange}))) "
			+ "OR (c.status IN ('HELD','CONSUMED') AND ((c.identity_type='REPORT_ID' "
			+ "AND c.identity_key=#{report}) OR (c.identity_type='IDENTIFIER' AND c.identity_key=#{identifier}))))")
	long occupied(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("task") String task,
			@Param("node") String node, @Param("arrangeId") String arrangeId, @Param("arrange") String arrange,
			@Param("report") String report, @Param("identifier") String identifier);
}
