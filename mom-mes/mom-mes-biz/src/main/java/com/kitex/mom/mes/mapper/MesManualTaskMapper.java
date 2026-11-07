package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.mes.entity.MesManualTaskRequestEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 手工建单原件及原来源核查；跨历史查询显式限定实际租户和工厂。 */
@Mapper
public interface MesManualTaskMapper extends BaseMapper<MesManualTaskRequestEntity> {
    @Select("SELECT mom_mes.mom_manual_task_ready_v1()")
    Integer ready();

    @Select("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(#{key},0))")
    Integer lockRequest(@Param("key") String key);

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select(
            "SELECT * FROM mom_mes.mes_manual_task_request WHERE tenant_id=#{tenant} AND"
                    + " factory_code=#{factory} AND request_id=#{request}")
    MesManualTaskRequestEntity receipt(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("request") String request);

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select(
            "SELECT * FROM mom_mes.mes_task WHERE tenant_id=#{tenant} AND factory_code=#{factory}"
                    + " AND source_request_id=#{request} ORDER BY id")
    List<MesTaskEntity> original(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("request") String request);

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select(
            "SELECT * FROM mom_mes.mes_task WHERE id=#{id} AND tenant_id=#{tenant} AND"
                    + " factory_code=#{factory} AND del_flag='0' FOR UPDATE")
    MesTaskEntity lockTask(
            @Param("id") Long id, @Param("tenant") Long tenant, @Param("factory") String factory);

	/**
	 * 核原父工单的全部APS分配历史，不因逻辑删除或人员数据过滤隐藏已发生的计划来源。
	 * 调用方须先持同工单根锁，查询无副作用，也不锁定或改写APS分配账。
	 */
	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
	@Select("""
			SELECT count(*)
			FROM mom_mes.mes_aps_batch_allocation
			WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND mes_task_id=#{task}
			""")
	long apsLinks(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("task") Long task);

	/**
	 * 首次手工过程组合读取原工单全部派工历史，含关闭、完工及逻辑删除行。
	 * 已发生的派工不能因当前列表不可见而重新获得首次运行资格。
	 */
	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
	@Select("""
			SELECT count(*)
			FROM mom_mes.mes_task_arrange
			WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND task_code=#{taskCode}
			""")
	long arrangementHistory(@Param("tenant") Long tenant, @Param("factory") String factory,
			@Param("taskCode") String taskCode);
}
