package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.kitex.mom.mes.entity.MesDispatchCommandEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 独立退派的只读配额来源与严格零执行查询。
 *
 * <p>显式限定真实租户、工厂和全局派工ID；锁后不复用会话第一次取证结果。
 * 结构探测仅区分完整前版与完全未安装，不把部分安装解释为可走历史旁路。</p>
 */
@Mapper
@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
public interface MesDispatchQuotaReturnMapper {

	/**
	 * 返回第400节结构存在性：0为全部未安装、1为基础入口均存在、2为部分安装。
	 * 1仍须调用原ready核完整目录，不能仅凭对象同名便判断就绪。
	 */
	@Select("SELECT CASE WHEN to_regclass('mom_mes.mes_dispatch_allocation') IS NULL "
			+ "AND to_regclass('mom_mes.mes_dispatch_command') IS NULL "
			+ "AND to_regprocedure('mom_mes.mes_dispatch_allocation_v1_ready()') IS NULL "
			+ "AND to_regprocedure('mom_mes.dispatch_no_execution_400(jsonb)') IS NULL THEN 0 "
			+ "WHEN to_regclass('mom_mes.mes_dispatch_allocation') IS NOT NULL "
			+ "AND to_regclass('mom_mes.mes_dispatch_command') IS NOT NULL "
			+ "AND to_regprocedure('mom_mes.mes_dispatch_allocation_v1_ready()') IS NOT NULL "
			+ "AND to_regprocedure('mom_mes.dispatch_no_execution_400(jsonb)') IS NOT NULL THEN 1 ELSE 2 END")
	@Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
	Integer structureState();

	/** 精确读取不可变出生命令，不按主工序或同码其他工厂取首条。 */
	@Select("SELECT * FROM mom_mes.mes_dispatch_command WHERE id=#{commandId} "
			+ "AND tenant_id=#{tenant} AND factory_code=#{factory}")
	@Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
	MesDispatchCommandEntity command(@Param("tenant") Long tenant, @Param("factory") String factory,
			@Param("commandId") Long commandId);

	/**
	 * 复用第400节权威零执行判定；包括已逆向/软删事实，NULL数量不等于0。
	 * 带真实派工ID的异常组织计时/单件等事实也阻断；本查询不输出其内容。
	 */
	@Select("SELECT mom_mes.dispatch_no_execution_400(to_jsonb(a)) FROM mom_mes.mes_task_arrange a "
			+ "WHERE a.id=#{arrangeId} AND a.tenant_id=#{tenant} AND a.factory_code=#{factory}")
	@Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
	Boolean noExecution(@Param("tenant") Long tenant, @Param("factory") String factory,
			@Param("arrangeId") Long arrangeId);
}
