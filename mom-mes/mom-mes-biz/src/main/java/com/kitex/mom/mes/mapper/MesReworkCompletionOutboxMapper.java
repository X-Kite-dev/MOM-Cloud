package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.mes.entity.MesReworkCompletionOutboxEntity;

import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface MesReworkCompletionOutboxMapper
        extends BaseMapper<MesReworkCompletionOutboxEntity> {
    @Select("SELECT mom_mes.mom_rework_completion_outbox_ready_v1()")
    Integer schemaVersion();

    @Select(
            "SELECT id,tenant_id,factory_code,task_id,request_id,CASE WHEN"
                    + " OCTET_LENGTH(payload)<=65536 THEN payload ELSE NULL END AS"
                    + " payload,payload_fingerprint,created_by,created_at FROM"
                    + " mom_mes.mes_rework_completion_outbox WHERE tenant_id=#{tenant} AND"
                    + " factory_code=#{factory} AND task_id=#{taskId} ORDER BY id LIMIT 2")
    @Results(
            id = "completionOriginal",
            value = {
                @Result(column = "id", property = "id"),
                @Result(column = "tenant_id", property = "tenantId"),
                @Result(column = "factory_code", property = "factoryCode"),
                @Result(column = "task_id", property = "taskId"),
                @Result(column = "request_id", property = "requestId"),
                @Result(column = "payload", property = "payload"),
                @Result(column = "payload_fingerprint", property = "payloadFingerprint"),
                @Result(column = "created_by", property = "createdBy"),
                @Result(column = "created_at", property = "createdAt")
            })
    List<MesReworkCompletionOutboxEntity> original(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("taskId") Long taskId);
}
