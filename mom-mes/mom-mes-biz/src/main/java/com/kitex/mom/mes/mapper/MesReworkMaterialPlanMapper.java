package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.mes.entity.MesReworkMaterialPlanEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MesReworkMaterialPlanMapper extends BaseMapper<MesReworkMaterialPlanEntity> {
    @Select("SELECT pg_advisory_xact_lock(hashtextextended(#{key},0))")
    void lockRequest(@Param("key") String key);

    // Deliberately includes reversed/logically deleted history; do not infer a fresh work order from net zero.
    @Select("""
            SELECT EXISTS(SELECT 1 FROM mes_task_report r WHERE r.tenant_id=#{tenant} AND r.factory_code=#{factory}
                AND (r.task_code=#{task} OR r.arrange_code IN (SELECT a.code FROM mes_task_arrange a
                    WHERE a.tenant_id=#{tenant} AND a.factory_code=#{factory} AND a.task_code=#{task})))
              OR EXISTS(SELECT 1 FROM mes_warehouse_feed f WHERE f.tenant_id=#{tenant} AND f.factory_code=#{factory}
                    AND f.task_code=#{task} AND (f.used_num IS NULL OR f.used_num<>0))
              OR EXISTS(SELECT 1 FROM mes_report_material_consume c WHERE c.tenant_id=#{tenant} AND c.factory_code=#{factory}
                    AND c.arrange_code IN (SELECT a.code FROM mes_task_arrange a WHERE a.tenant_id=#{tenant}
                        AND a.factory_code=#{factory} AND a.task_code=#{task}))
            """)
    boolean hasExecutionHistory(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("task") String task);
}
