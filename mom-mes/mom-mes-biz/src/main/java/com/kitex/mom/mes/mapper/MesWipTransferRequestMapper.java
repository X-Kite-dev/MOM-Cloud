package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.kitex.mom.mes.entity.MesWipTransferOperationEntity;

import lombok.Data;

import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
public interface MesWipTransferRequestMapper {
    @Data
    class CancellationRow {
        private Long tenantId, actorId, canceledAt;
        private String factoryCode, requestId, operationType, commandJson, fingerprint, reason;
    }

    @Select(
            "SELECT CASE WHEN to_regclass('mom_mes.mes_wip_transfer_request_cancel') IS NULL THEN 0"
                    + " ELSE 1 END")
    int installed();

    @Select("SELECT mom_mes.mom_wip_transfer_request_ready_v1()")
    Integer ready();

    @Select("SELECT current_setting('transaction_isolation')")
    String isolation();

    @Select(
            "SELECT"
                + " id,tenant_id,factory_code,transfer_id,request_id,fingerprint,operation_type,sequence_no,operator_id,quantity,before_remaining,after_remaining,result_stock_id,source_before_num,source_after_num,before_status,after_status,reason,create_time,del_flag"
                + " FROM mom_mes.mes_wip_transfer_operation WHERE tenant_id=#{tenant} AND"
                + " factory_code=#{factory} AND request_id=#{request} ORDER BY id LIMIT 2")
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    List<MesWipTransferOperationEntity> operations(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("request") String request);

    @Select(
            "SELECT * FROM mom_mes.mes_wip_transfer_request_cancel WHERE tenant_id=#{tenant} AND"
                    + " factory_code=#{factory} AND request_id=#{request} LIMIT 2")
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    List<CancellationRow> cancellations(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("request") String request);

    @Insert(
            "INSERT INTO"
                + " mom_mes.mes_wip_transfer_request_cancel(tenant_id,factory_code,request_id,actor_id,operation_type,command_json,fingerprint,reason,canceled_at)"
                + " VALUES(#{tenantId},#{factoryCode},#{requestId},#{actorId},#{operationType},#{commandJson},#{fingerprint},#{reason},#{canceledAt})")
    int insertCancel(CancellationRow row);
}
