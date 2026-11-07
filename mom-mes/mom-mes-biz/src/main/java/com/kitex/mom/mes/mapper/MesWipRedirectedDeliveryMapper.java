package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;

import org.apache.ibatis.annotations.*;

@Mapper
@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
public interface MesWipRedirectedDeliveryMapper {
    @Select(
            "SELECT CASE WHEN to_regprocedure('mom_mes.mom_wip_redirected_delivery_ready_v1()') IS"
                    + " NULL THEN 0 ELSE 1 END")
    int installed();

    @Select("SELECT mom_mes.mom_wip_redirected_delivery_ready_v1()")
    Integer ready();
}
