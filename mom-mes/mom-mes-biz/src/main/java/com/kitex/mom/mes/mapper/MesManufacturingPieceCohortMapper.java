package com.kitex.mom.mes.mapper;

import com.kitex.mom.mes.entity.MesTaskEntity;

import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 限定实际组织、工单与服务端登记UTC周期的只读总体查询，保留额外一行以识别超限。
 */
@Mapper
@com.baomidou.mybatisplus.annotation.InterceptorIgnore(tenantLine = "true", dataPermission = "true")
public interface MesManufacturingPieceCohortMapper {
	/**
	 * 按实际租户工厂和精确工单编码读取原工单，返回最多两行用于拒绝不唯一。
	 */
	@Select(
			"SELECT * FROM mom_mes.mes_task WHERE tenant_id=#{tenant} AND factory_code=#{factory}"
					+ " AND code=#{code} AND del_flag='0' LIMIT 2")
	List<MesTaskEntity> order(
			@Param("tenant") Long tenant,
			@Param("factory") String factory,
			@Param("code") String code);

	/**
	 * 按实际组织、工单和服务端登记半开周期读取完整身份候选，最多返回1001行以识别超过1000的范围。
	 */
	@Select(
			"SELECT * FROM mom_mes.mes_manufacturing_piece WHERE tenant_id=#{tenant} AND"
					+ " factory_code=#{factory} AND task_id=#{task} AND"
					+ " (payload::jsonb->>'registeredAt')::bigint >= #{from} AND"
					+ " (payload::jsonb->>'registeredAt')::bigint < #{to} LIMIT 1001")
	List<MesManufacturingPieceMapper.Row> members(
			@Param("tenant") Long tenant,
			@Param("factory") String factory,
			@Param("task") Long task,
			@Param("from") long from,
			@Param("to") long to);
}
