package com.kitex.mom.mdm.mapper;

import com.kitex.mom.mdm.entity.*;

import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * Read-only exact current organisation. Draft policy and original rows use one snapshot.
 */
@Mapper
@com.baomidou.mybatisplus.annotation.InterceptorIgnore(tenantLine = "true", dataPermission = "true")
public interface MdmRouteMaterialConsumptionMapper {
	/**
	 * 限定实际租户工厂与路线编码读取原配置，最多两行用于拒绝不唯一。
	 */
	@Select(
			"SELECT * FROM mdm_process_route WHERE tenant_id=#{tenant} AND factory_code=#{factory}"
					+ " AND route_code=#{route} AND del_flag='0' LIMIT 2")
	List<MdmProcessRouteEntity> route(
			@Param("tenant") Long tenant,
			@Param("factory") String factory,
			@Param("route") String route);

	/**
	 * 读取当前实际组织与稳定节点的原物料行，最多101行用于拒绝超过100项的配置。
	 */
	@Select(
			"SELECT * FROM mdm_process_procedure_mat WHERE tenant_id=#{tenant} AND"
					+ " factory_code=#{factory} AND route_code=#{route} AND route_node_code=#{node} AND"
					+ " del_flag='0' ORDER BY step,id LIMIT 101")
	List<MdmProcessProcedureMatEntity> materials(
			@Param("tenant") Long tenant,
			@Param("factory") String factory,
			@Param("route") String route,
			@Param("node") String node);
}
