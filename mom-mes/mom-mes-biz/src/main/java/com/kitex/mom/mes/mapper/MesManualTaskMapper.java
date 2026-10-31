package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.mes.entity.MesManualTaskRequestEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface MesManualTaskMapper extends BaseMapper<MesManualTaskRequestEntity> {
    @Select("SELECT mom_mes.mom_manual_task_ready_v1()")
    Integer ready();

    @Select("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(#{key},0))")
    Integer lockRequest(@Param("key") String key);

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select(
            "SELECT * FROM mom_mes.mes_manual_task_request WHERE tenant_id=#{tenant} AND"
                    + " factory_code=#{factory} AND request_id=#{request}")
    MesManualTaskRequestEntity receipt(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("request") String request);

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select(
            "SELECT * FROM mom_mes.mes_task WHERE tenant_id=#{tenant} AND factory_code=#{factory}"
                    + " AND source_request_id=#{request} ORDER BY id")
    List<MesTaskEntity> original(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("request") String request);

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select(
            "SELECT * FROM mom_mes.mes_task WHERE id=#{id} AND tenant_id=#{tenant} AND"
                    + " factory_code=#{factory} AND del_flag='0' FOR UPDATE")
    MesTaskEntity lockTask(
            @Param("id") Long id, @Param("tenant") Long tenant, @Param("factory") String factory);
}
