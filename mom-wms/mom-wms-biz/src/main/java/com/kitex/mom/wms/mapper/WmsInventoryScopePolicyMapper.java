package com.kitex.mom.wms.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 盘点默认范围按租户、工厂和仓库精确隔离。 */
@Mapper
public interface WmsInventoryScopePolicyMapper {
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("SELECT rule_code AS ruleCode, threshold_days AS thresholdDays, version, "
            + "inherit_default AS inheritDefault "
            + "FROM mom_wms.wms_inventory_scope_policy "
            + "WHERE tenant_id = #{tenant} AND factory_code = #{factory} AND warehouse_code = #{warehouse}")
    Row find(@Param("tenant") Long tenant, @Param("factory") String factory,
             @Param("warehouse") String warehouse);

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Insert("INSERT INTO mom_wms.wms_inventory_scope_policy AS policy "
            + "(tenant_id, factory_code, warehouse_code, rule_code, threshold_days, "
            + "inherit_default, version, updated_by) "
            + "VALUES (#{tenant}, #{factory}, #{warehouse}, #{ruleCode}, #{thresholdDays}, false, 1, #{updatedBy}) "
            + "ON CONFLICT (tenant_id, factory_code, warehouse_code) DO UPDATE SET "
            + "rule_code = EXCLUDED.rule_code, threshold_days = EXCLUDED.threshold_days, "
            + "inherit_default = false, version = policy.version + 1, "
            + "updated_at = now(), updated_by = EXCLUDED.updated_by "
            + "WHERE policy.version = #{expectedVersion}")
    int upsert(@Param("tenant") Long tenant, @Param("factory") String factory,
               @Param("warehouse") String warehouse, @Param("ruleCode") String ruleCode,
               @Param("thresholdDays") Integer thresholdDays, @Param("expectedVersion") int expectedVersion,
               @Param("updatedBy") String updatedBy);

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Update("UPDATE mom_wms.wms_inventory_scope_policy SET rule_code = 'ALL_POSITIVE', "
            + "threshold_days = NULL, inherit_default = true, version = version + 1, "
            + "updated_at = now(), updated_by = #{updatedBy} WHERE tenant_id = #{tenant} "
            + "AND factory_code = #{factory} AND warehouse_code = #{warehouse} "
            + "AND version = #{expectedVersion} AND inherit_default = false")
    int resetVersion(@Param("tenant") Long tenant, @Param("factory") String factory,
                     @Param("warehouse") String warehouse, @Param("expectedVersion") int expectedVersion,
                     @Param("updatedBy") String updatedBy);

    record Row(String ruleCode, Integer thresholdDays, int version, boolean inheritDefault) {
    }
}
