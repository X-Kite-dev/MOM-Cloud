package com.kitex.mom.mes.mapper;

import com.kitex.mom.mes.entity.MesProduceTaskEntity;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.entity.MesTaskReportEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 在制影响专用只读查询，所有读取显式约束真实租户、工厂及原工单。 */
@Mapper
public interface MesInFlightEngineeringImpactMapper {
	/** 按当前组织精确取工单；无记录由服务层拒绝，不跨组织取首条。 */
	@Select("SELECT * FROM mom_mes.mes_task WHERE tenant_id=#{tenant} AND factory_code=#{factory} "
			+ "AND code=#{task} AND del_flag='0'")
	MesTaskEntity task(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("task") String task);

	/** 读取工单全部工序（含软删除历史），额外一行使服务层明确拒绝截断。 */
	@Select("SELECT * FROM mom_mes.mes_produce_task WHERE tenant_id=#{tenant} AND factory_code=#{factory} "
			+ "AND task_code=#{task} ORDER BY id LIMIT 1001")
	List<MesProduceTaskEntity> processes(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("task") String task);

	/** 包括已关闭、已完成及软删除历史派工，不只观察当前活动记录。 */
	@Select("SELECT * FROM mom_mes.mes_task_arrange WHERE tenant_id=#{tenant} AND factory_code=#{factory} "
			+ "AND task_code=#{task} ORDER BY id LIMIT 10001")
	List<MesTaskArrangeEntity> arranges(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("task") String task);

	/** 包括冲销和软删除历史报工；无投料feed不能从此查询推断WMS无占用。 */
	@Select("SELECT * FROM mom_mes.mes_task_report WHERE tenant_id=#{tenant} AND factory_code=#{factory} "
			+ "AND task_code=#{task} ORDER BY id LIMIT 20001")
	List<MesTaskReportEntity> reports(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("task") String task);
}
