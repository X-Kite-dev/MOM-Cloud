package com.kitex.mom.wms.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.kitex.mom.wms.dto.WmsWarehouseCapacityDTO.StockGroup;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 容量配置与布局共享版本；所有SQL显式限定组织和仓库。
 */
@Mapper
public interface WmsWarehouseCapacityMapper {
	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Select("SELECT capacity_json::text AS capacityJson, version FROM mom_wms.wms_warehouse_location_map "
			+ "WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND warehouse_code=#{warehouse}")
	Row find(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("warehouse") String warehouse);

	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Insert("INSERT INTO mom_wms.wms_warehouse_location_map "
			+ "(tenant_id,factory_code,warehouse_code,layout_json,capacity_json,version,updated_by) "
			+ "VALUES (#{tenant},#{factory},#{warehouse},'[]'::jsonb,CAST(#{modelJson} AS jsonb),1,#{updatedBy}) "
			+ "ON CONFLICT (tenant_id,factory_code,warehouse_code) DO NOTHING")
	int insert(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("warehouse") String warehouse,
	           @Param("modelJson") String modelJson, @Param("updatedBy") String updatedBy);

	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Update("UPDATE mom_wms.wms_warehouse_location_map SET capacity_json=CAST(#{modelJson} AS jsonb), "
			+ "version=version+1,updated_at=now(),updated_by=#{updatedBy} "
			+ "WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND warehouse_code=#{warehouse} AND version=#{expectedVersion}")
	int update(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("warehouse") String warehouse,
	           @Param("modelJson") String modelJson, @Param("expectedVersion") int expectedVersion, @Param("updatedBy") String updatedBy);

	String VALID_QUANTITY = "mat_num > 0 AND CAST(mat_num AS text) NOT IN ('NaN','Infinity','-Infinity')";

	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Select("<script>SELECT COALESCE(site_code,'') AS siteCode, mat_code AS matCode, MIN(mat_name) AS matName,mat_unit AS matUnit, "
			+ "SUM(CASE WHEN " + VALID_QUANTITY + " THEN CAST(CAST(mat_num AS text) AS numeric) ELSE 0 END)::text AS quantity, "
			+ "SUM(CASE WHEN " + VALID_QUANTITY + " THEN 1 ELSE 0 END) AS stockRows, "
			+ "SUM(CASE WHEN mat_num IS NULL OR NOT (" + VALID_QUANTITY + ") THEN 1 ELSE 0 END) AS invalidRows "
			+ "FROM mom_wms.wms_live_stock WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND warehouse_code=#{warehouse} "
			+ "AND del_flag='0' AND (mat_num != 0 OR mat_num IS NULL) "
			+ "<if test='site != null'>AND COALESCE(site_code,'')=#{site} </if>"
			+ "GROUP BY COALESCE(site_code,''),mat_code,mat_unit ORDER BY COALESCE(site_code,''),mat_code,mat_unit LIMIT 50001</script>")
	List<StockGroup> groups(@Param("tenant") Long tenant, @Param("factory") String factory,
	                        @Param("warehouse") String warehouse, @Param("site") String site);

	record Row(String capacityJson, int version) {
	}
}
