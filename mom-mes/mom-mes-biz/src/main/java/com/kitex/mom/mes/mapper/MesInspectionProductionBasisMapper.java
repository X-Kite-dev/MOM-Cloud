package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.kitex.mom.mes.entity.*;

import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * Internal cross-domain proof uses explicit actual organisation predicates, not caller row filters.
 */
@Mapper
public interface MesInspectionProductionBasisMapper {
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select(
            "SELECT * FROM mom_mes.mes_produce_task WHERE tenant_id=#{tenant} AND"
                    + " factory_code=#{factory} AND code=#{code} AND del_flag='0' LIMIT 2")
    List<MesProduceTaskEntity> operation(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("code") String code);

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select(
            "SELECT * FROM mom_mes.mes_task WHERE tenant_id=#{tenant} AND factory_code=#{factory}"
                    + " AND code=#{code} AND del_flag='0' LIMIT 2")
    List<MesTaskEntity> order(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("code") String code);
}
