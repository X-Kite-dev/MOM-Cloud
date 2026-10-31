package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.kitex.mom.mes.entity.MesExecutionCommandCancellationEntity;
import org.apache.ibatis.annotations.*;

/**
 * 只查和新增永久墓碑；每次原号查询清空SESSION缓存，组织范围由已认证的调用者明确绑定。
 */
@Mapper
public interface MesExecutionCommandCancellationMapper {

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("SELECT * FROM mom_mes.mes_execution_command_cancellation WHERE tenant_id=#{tenant} "
            + "AND factory_code=#{factory} AND command_kind=#{kind} AND request_id=#{request}")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    MesExecutionCommandCancellationEntity findOriginal(@Param("tenant") Long tenant, @Param("factory") String factory,
                                                       @Param("kind") String kind, @Param("request") String request);

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Insert("INSERT INTO mom_mes.mes_execution_command_cancellation(id,tenant_id,factory_code,command_kind,"
            + "request_id,request_hash,actor_id,create_by,create_time,del_flag) VALUES(#{id},#{tenantId},#{factoryCode},"
            + "#{commandKind},#{requestId},#{requestHash},#{actorId},#{createBy},#{createTime},#{delFlag})")
    int insert(MesExecutionCommandCancellationEntity row);
}
