package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.mes.dto.MesInspectionRecordWorkbenchDTO.Query;
import com.kitex.mom.mes.entity.MesInspectionGenRecordEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 质检任务生成记录 Mapper 接口
 *
 * @author kitex
 * @since 2024-02-06
 */
@Mapper
public interface MesInspectionGenRecordMapper extends BaseMapper<MesInspectionGenRecordEntity> {
    /** 后台任务发现待处理组织，具体排程及工序在恢复组织后读取。 */
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("""
            SELECT DISTINCT tenant_id, factory_code
            FROM mom_mes.mes_inspection_gen_record
            WHERE del_flag = '0' AND tenant_id > 0 AND NULLIF(btrim(factory_code), '') IS NOT NULL
              AND (next_time <= #{observedAt}
                   OR (next_time IS NULL AND NULLIF(btrim(next_shift), '') IS NOT NULL))
            ORDER BY tenant_id, factory_code
            """)
    List<MesInspectionGenRecordEntity> findPatrolOrganizations(@Param("observedAt") LocalDateTime observedAt);

    Long workbenchCount(@Param("tenant") Long tenant,
                        @Param("factory") String factory,
                        @Param("q") Query query);
    List<MesInspectionGenRecordEntity> workbenchRows(@Param("tenant") Long tenant,
                        @Param("factory") String factory,
                        @Param("q") Query query,
                        @Param("offset") long offset,
                        @Param("limit") int limit);
    MesInspectionGenRecordEntity workbenchDetail(@Param("tenant") Long tenant,
                        @Param("factory") String factory,
                        @Param("id") Long id);

}
