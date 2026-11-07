package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.kitex.mom.mes.entity.MesWipTransferEntity;
import com.kitex.mom.mes.mapper.MesWipRedirectReviewMapper.RequestRow;

import lombok.Data;

import org.apache.ibatis.annotations.*;

@Mapper
@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
public interface MesWipRedirectActivationMapper {
    @Data
    class EffectRow {
        private Long tenantId, createdAt;
        private String factoryCode, requestId, transferId, effectJson, effectHash, reviewJson;
    }

    @Select(
            "SELECT (CASE WHEN to_regclass('mom_mes.mes_wip_redirect_activation_command') IS NULL"
                + " THEN 0 ELSE 1 END)+(CASE WHEN to_regclass('mom_mes.mes_wip_redirect_effect') IS"
                + " NULL THEN 0 ELSE 1 END)")
    int installed();

    @Select("SELECT mom_mes.mom_wip_redirect_activation_ready_v1()")
    Integer ready();

    @Select("SELECT current_setting('transaction_isolation')")
    String isolation();

    String TRANSFER =
            "SELECT "
                    + MesWipDestinationChangeMapper.TRANSFER
                    + " FROM mom_mes.mes_wip_transfer WHERE tenant_id=#{tenant} AND"
                    + " factory_code=#{factory} AND id=#{id} AND del_flag='0'";

    @Select(TRANSFER)
    MesWipTransferEntity transfer(
            @Param("tenant") Long t, @Param("factory") String f, @Param("id") Long id);

    @Select(TRANSFER + " FOR UPDATE")
    MesWipTransferEntity lockTransfer(
            @Param("tenant") Long t, @Param("factory") String f, @Param("id") Long id);

    @Select(
            "SELECT id FROM mom_mes.mes_task_report WHERE tenant_id=#{tenant} AND"
                    + " factory_code=#{factory} AND id=#{id} FOR UPDATE")
    Long lockReport(@Param("tenant") Long t, @Param("factory") String f, @Param("id") Long id);

    @Update(
            "UPDATE mom_mes.mes_wip_transfer SET revision=revision+1 WHERE tenant_id=#{tenant} AND"
                + " factory_code=#{factory} AND id=#{id} AND del_flag='0' AND status='TRANSIT' AND"
                + " revision=#{revision}")
    int advance(
            @Param("tenant") Long t,
            @Param("factory") String f,
            @Param("id") Long id,
            @Param("revision") Long revision);

    @Select(
            "SELECT"
                + " tenant_id,factory_code,request_id,actor_id,command_json,fingerprint,outcome,cancellation_reason,created_at"
                + " FROM mom_mes.mes_wip_redirect_activation_command WHERE tenant_id=#{tenant} AND"
                + " factory_code=#{factory} AND request_id=#{request}")
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    RequestRow request(
            @Param("tenant") Long t, @Param("factory") String f, @Param("request") String id);

    @Insert(
            "INSERT INTO"
                + " mom_mes.mes_wip_redirect_activation_command(tenant_id,factory_code,request_id,actor_id,command_json,fingerprint,outcome,cancellation_reason,created_at)"
                + " VALUES(#{tenantId},#{factoryCode},#{requestId},#{actorId},#{commandJson},#{fingerprint},#{outcome},#{cancellationReason},#{createdAt})")
    int insertRequest(RequestRow row);

    @Select(
            "SELECT"
                + " tenant_id,factory_code,request_id,transfer_id,effect_json,effect_hash,review_json,created_at"
                + " FROM mom_mes.mes_wip_redirect_effect WHERE tenant_id=#{tenant} AND"
                + " factory_code=#{factory} AND transfer_id=#{transfer}")
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    EffectRow effect(
            @Param("tenant") Long t, @Param("factory") String f, @Param("transfer") String id);

    @Select(
            "<script>SELECT"
                + " tenant_id,factory_code,request_id,transfer_id,effect_json,effect_hash,review_json,created_at"
                + " FROM mom_mes.mes_wip_redirect_effect WHERE tenant_id=#{tenant} AND"
                + " factory_code=#{factory} AND transfer_id IN <foreach collection='ids' item='id'"
                + " open='(' separator=',' close=')'>#{id}</foreach></script>")
    java.util.List<EffectRow> effects(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("ids") java.util.List<String> ids);

    @Insert(
            "INSERT INTO"
                + " mom_mes.mes_wip_redirect_effect(tenant_id,factory_code,request_id,transfer_id,effect_json,effect_hash,review_json,created_at)"
                + " VALUES(#{tenantId},#{factoryCode},#{requestId},#{transferId},#{effectJson},#{effectHash},#{reviewJson},#{createdAt})")
    int insertEffect(EffectRow row);
}
