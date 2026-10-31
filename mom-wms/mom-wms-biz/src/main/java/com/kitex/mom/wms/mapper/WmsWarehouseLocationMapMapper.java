package com.kitex.mom.wms.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 库位图按已校验的租户、工厂、仓库精确读写。 */
@Mapper
public interface WmsWarehouseLocationMapMapper {
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("SELECT layout_json::text AS layoutJson, version "
            + "FROM mom_wms.wms_warehouse_location_map "
            + "WHERE tenant_id = #{tenant} AND factory_code = #{factory} AND warehouse_code = #{warehouse}")
    Row find(@Param("tenant") Long tenant, @Param("factory") String factory,
             @Param("warehouse") String warehouse);

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Insert("INSERT INTO mom_wms.wms_warehouse_location_map AS current_map "
            + "(tenant_id, factory_code, warehouse_code, layout_json, version, updated_by) "
            + "VALUES (#{tenant}, #{factory}, #{warehouse}, CAST(#{layoutJson} AS jsonb), 1, #{updatedBy}) "
            + "ON CONFLICT (tenant_id, factory_code, warehouse_code) DO UPDATE SET "
            + "layout_json = EXCLUDED.layout_json, version = current_map.version + 1, "
            + "updated_at = now(), updated_by = EXCLUDED.updated_by "
            + "WHERE current_map.version = #{expectedVersion}")
    int upsert(@Param("tenant") Long tenant, @Param("factory") String factory,
               @Param("warehouse") String warehouse, @Param("layoutJson") String layoutJson,
               @Param("expectedVersion") int expectedVersion, @Param("updatedBy") String updatedBy);

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Delete("DELETE FROM mom_wms.wms_warehouse_location_map "
            + "WHERE tenant_id = #{tenant} AND factory_code = #{factory} AND warehouse_code = #{warehouse}")
    int delete(@Param("tenant") Long tenant, @Param("factory") String factory,
               @Param("warehouse") String warehouse);

    record Row(String layoutJson, int version) {
    }
}
