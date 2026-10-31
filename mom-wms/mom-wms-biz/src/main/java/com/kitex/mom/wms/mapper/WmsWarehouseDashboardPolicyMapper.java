package com.kitex.mom.wms.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 看板风险阈值只按调用方已校验的租户、工厂和仓库精确读写。 */
@Mapper
public interface WmsWarehouseDashboardPolicyMapper {
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("SELECT near_expiry_days AS nearExpiryDays, slow_moving_days AS slowMovingDays, version "
            + "FROM mom_wms.wms_warehouse_dashboard_policy "
            + "WHERE tenant_id = #{tenant} AND factory_code = #{factory} AND warehouse_code = #{warehouse}")
    Row find(@Param("tenant") Long tenant, @Param("factory") String factory,
             @Param("warehouse") String warehouse);

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Insert("INSERT INTO mom_wms.wms_warehouse_dashboard_policy AS policy "
            + "(tenant_id, factory_code, warehouse_code, near_expiry_days, slow_moving_days, version, updated_by) "
            + "VALUES (#{tenant}, #{factory}, #{warehouse}, #{nearExpiryDays}, #{slowMovingDays}, 1, #{updatedBy}) "
            + "ON CONFLICT (tenant_id, factory_code, warehouse_code) DO UPDATE SET "
            + "near_expiry_days = EXCLUDED.near_expiry_days, slow_moving_days = EXCLUDED.slow_moving_days, "
            + "version = policy.version + 1, "
            + "updated_at = now(), updated_by = EXCLUDED.updated_by "
            + "WHERE policy.version = #{expectedVersion}")
    int upsert(@Param("tenant") Long tenant, @Param("factory") String factory,
               @Param("warehouse") String warehouse, @Param("nearExpiryDays") int nearExpiryDays,
               @Param("slowMovingDays") int slowMovingDays,
               @Param("expectedVersion") int expectedVersion, @Param("updatedBy") String updatedBy);

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Delete("DELETE FROM mom_wms.wms_warehouse_dashboard_policy "
            + "WHERE tenant_id = #{tenant} AND factory_code = #{factory} "
            + "AND warehouse_code = #{warehouse} AND version = #{expectedVersion}")
    int deleteVersion(@Param("tenant") Long tenant, @Param("factory") String factory,
                      @Param("warehouse") String warehouse, @Param("expectedVersion") int expectedVersion);

    record Row(int nearExpiryDays, int slowMovingDays, int version) {
    }
}
