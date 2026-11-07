package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.kitex.mom.mes.entity.MesWipTransferEntity;

import lombok.Data;

import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
public interface MesWipRedirectReviewMapper {
    @Data
    class RequestRow {
        private Long tenantId, actorId, createdAt;
        private String factoryCode,
                requestId,
                commandJson,
                fingerprint,
                outcome,
                cancellationReason;
    }

    @Data
    class EventRow {
        private Long tenantId, actorId, createdAt;
        private Integer sequenceNo;
        private String factoryCode,
                requestId,
                proposalId,
                transferId,
                action,
                beforeState,
                afterState,
                actorCode,
                evidenceJson,
                evidenceHash,
                reviewJson;
    }

    @Select(
            "SELECT (CASE WHEN to_regclass('mom_mes.mes_wip_redirect_review_command') IS NULL THEN"
                + " 0 ELSE 1 END)+(CASE WHEN to_regclass('mom_mes.mes_wip_redirect_review_event')"
                + " IS NULL THEN 0 ELSE 1 END)")
    int installed();

    @Select("SELECT mom_mes.mom_wip_redirect_review_ready_v1()")
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
            @Param("tenant") Long tenant, @Param("factory") String factory, @Param("id") Long id);

    @Select(TRANSFER + " FOR UPDATE")
    MesWipTransferEntity lockTransfer(
            @Param("tenant") Long tenant, @Param("factory") String factory, @Param("id") Long id);

    @Select(
            "SELECT id FROM mom_mes.mes_task_report WHERE tenant_id=#{tenant} AND"
                + " factory_code=#{factory} AND id=#{id} AND status='EFFECTIVE' AND del_flag='0'"
                + " FOR UPDATE")
    Long lockReport(
            @Param("tenant") Long tenant, @Param("factory") String factory, @Param("id") Long id);

    @Select(
            "SELECT"
                + " tenant_id,factory_code,request_id,actor_id,command_json,fingerprint,outcome,cancellation_reason,created_at"
                + " FROM mom_mes.mes_wip_redirect_review_command WHERE tenant_id=#{tenant} AND"
                + " factory_code=#{factory} AND request_id=#{request}")
    RequestRow request(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("request") String request);

    @Insert(
            "INSERT INTO"
                + " mom_mes.mes_wip_redirect_review_command(tenant_id,factory_code,request_id,actor_id,command_json,fingerprint,outcome,cancellation_reason,created_at)"
                + " VALUES(#{tenantId},#{factoryCode},#{requestId},#{actorId},#{commandJson},#{fingerprint},#{outcome},#{cancellationReason},#{createdAt})")
    int insertRequest(RequestRow row);

    String EVENT =
            "tenant_id,factory_code,request_id,proposal_id,transfer_id,sequence_no,actor_id,actor_code,action,before_state,after_state,evidence_json,evidence_hash,review_json,created_at";

    @Select(
            "SELECT "
                    + EVENT
                    + " FROM mom_mes.mes_wip_redirect_review_event WHERE tenant_id=#{tenant} AND"
                    + " factory_code=#{factory} AND request_id=#{request}")
    EventRow event(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("request") String request);

    @Select(
            "SELECT "
                    + EVENT
                    + " FROM mom_mes.mes_wip_redirect_review_event WHERE tenant_id=#{tenant} AND"
                    + " factory_code=#{factory} AND proposal_id=#{proposal} ORDER BY sequence_no"
                    + " LIMIT 4")
    List<EventRow> events(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("proposal") String proposal);

    @Select(
            "SELECT proposal_id FROM mom_mes.mes_wip_redirect_review_event WHERE"
                + " tenant_id=#{tenant} AND factory_code=#{factory} AND transfer_id=#{transfer} AND"
                + " sequence_no=1 ORDER BY created_at DESC,proposal_id DESC LIMIT 21")
    List<String> proposals(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("transfer") String transfer);

    @Insert(
            "INSERT INTO"
                + " mom_mes.mes_wip_redirect_review_event(tenant_id,factory_code,request_id,proposal_id,transfer_id,sequence_no,actor_id,actor_code,action,before_state,after_state,evidence_json,evidence_hash,review_json,created_at)"
                + " VALUES(#{tenantId},#{factoryCode},#{requestId},#{proposalId},#{transferId},#{sequenceNo},#{actorId},#{actorCode},#{action},#{beforeState},#{afterState},#{evidenceJson},#{evidenceHash},#{reviewJson},#{createdAt})")
    int insertEvent(EventRow row);
}
