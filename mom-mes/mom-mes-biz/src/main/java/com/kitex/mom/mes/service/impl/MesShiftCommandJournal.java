package com.kitex.mom.mes.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 正式交接与遗留办理共用的永久命令占号账。
 * 占号独立提交；业务失败后原号仍被原动作、操作者和载荷占用。
 * 业务提交与成功回执同事务，取消只封存未执行请求，绝不撤销已提交业务。
 */
@Service
@RequiredArgsConstructor
public class MesShiftCommandJournal {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
            .registerModule(new SimpleModule().addSerializer(Long.class, ToStringSerializer.instance))
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final JdbcTemplate jdbc;

    /** 必须在业务事务开始前调用，避免可重复读事务看不到独立占号事务。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void reserve(String kind, String factory, String requestId, String action, String hash) {
        validate(kind, requestId);
        lock(kind, factory, requestId);
        Map<String, Object> row = row(kind, factory, requestId, true);
        if (row == null) {
            // 历史成功请求已占用其身份，不能让错误重试先插一条新PENDING劫持旧成功回执。
            String legacySql = "HANDOVER".equals(kind)
                    ? "SELECT action,operator_id AS actor_id,fingerprint AS command_hash FROM mom_mes.mes_shift_handover_operation WHERE tenant_id=? AND factory_code=? AND request_id=?"
                    : "SELECT action,actor_id,command_hash FROM mom_mes.mes_shift_handover_leftover_operation WHERE tenant_id=? AND factory_code=? AND request_id=?";
            var legacy = jdbc.queryForList(legacySql, tenant(), factory, requestId);
            if (!legacy.isEmpty()) {
                owner(legacy.get(0));
                require(action.equals(legacy.get(0).get("action")) && hash.equals(legacy.get(0).get("command_hash")),
                        "原号已有历史成功回执，不能变更人员、动作或内容");
            }
            jdbc.update("INSERT INTO mom_mes.mes_shift_command(tenant_id,factory_code,command_kind,request_id,action,actor_id,command_hash,status) VALUES(?,?,?,?,?,?,?,'PENDING')",
                    tenant(), factory, kind, requestId, action, actor(), hash);
        } else {
            owner(row);
            require(!"CANCELLED".equals(row.get("status")), "原请求已明确封存；迟到命令不得再次执行");
            require(action.equals(row.get("action")) && hash.equals(row.get("command_hash")), "同一请求号不能变更动作、操作者或载荷；先核原账");
        }
    }

    /** 行锁覆盖整个业务事务；与封存串行，禁止检查后被取消仍执行。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public Object begin(String kind, String factory, String requestId, String action, String hash) {
        Map<String, Object> row = row(kind, factory, requestId, true);
        require(row != null, "命令尚未永久占号");
        owner(row);
        require(!"CANCELLED".equals(row.get("status")), "原请求已封存，不能执行迟到命令");
        require(action.equals(row.get("action")) && hash.equals(row.get("command_hash")), "原命令载荷或动作不一致");
        return "SUCCEEDED".equals(row.get("status")) ? result(row) : null;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Object succeed(String kind, String factory, String requestId, Object result) {
        String value;
        try { value = JSON.writeValueAsString(result); }
        catch (Exception ex) { throw new CheckedException("交接原回执序列化失败"); }
        require(jdbc.update("UPDATE mom_mes.mes_shift_command SET status='SUCCEEDED',result_json=?,finished_at=now() WHERE tenant_id=? AND factory_code=? AND command_kind=? AND request_id=? AND actor_id=? AND status='PENDING'",
                value, tenant(), factory, kind, requestId, actor()) == 1, "命令已终结或作用域不一致");
        return result;
    }

    public Object receipt(String kind, String factory, String requestId) {
        validate(kind, requestId);
        var row = row(kind, factory, requestId, false);
        if (row == null) return null;
        owner(row);
        return result(row);
    }

    /** 无原号也写取消墓碑，阻断尚在网络中、还未到达占号步骤的迟到命令。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public Object cancel(String kind, String factory, String requestId) {
        validate(kind, requestId);
        lock(kind, factory, requestId);
        var row = row(kind, factory, requestId, true);
        if (row == null) {
            String table = "HANDOVER".equals(kind) ? "mes_shift_handover_operation" : "mes_shift_handover_leftover_operation";
            Long legacy = jdbc.queryForObject("SELECT count(*) FROM mom_mes." + table + " WHERE tenant_id=? AND factory_code=? AND request_id=?",
                    Long.class, tenant(), factory, requestId);
            require(legacy == null || legacy == 0, "原业务操作已经入账，请查询原回执；不能封存已执行操作");
            jdbc.update("INSERT INTO mom_mes.mes_shift_command(tenant_id,factory_code,command_kind,request_id,action,actor_id,status,finished_at) VALUES(?,?,?,?,?,?,'CANCELLED',now())",
                    tenant(), factory, kind, requestId, "CANCEL_UNEXECUTED", actor());
        } else {
            owner(row);
            if (!"PENDING".equals(row.get("status"))) return result(row);
            jdbc.update("UPDATE mom_mes.mes_shift_command SET status='CANCELLED',finished_at=now() WHERE tenant_id=? AND factory_code=? AND command_kind=? AND request_id=? AND status='PENDING'",
                    tenant(), factory, kind, requestId);
        }
        return result(Objects.requireNonNull(row(kind, factory, requestId, false)));
    }

    private Map<String, Object> row(String kind, String factory, String request, boolean locked) {
        var rows = jdbc.queryForList("SELECT * FROM mom_mes.mes_shift_command WHERE tenant_id=? AND factory_code=? AND command_kind=? AND request_id=?" + (locked ? " FOR UPDATE" : ""),
                tenant(), factory, kind, request);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Object result(Map<String, Object> row) {
        if ("SUCCEEDED".equals(row.get("status"))) {
            try {
                ObjectNode result = (ObjectNode) JSON.readTree((String) row.get("result_json"));
                result.put("commandStatus", "SUCCEEDED");
                return result;
            } catch (Exception ex) { throw new CheckedException("交接永久回执损坏，不能推断未执行"); }
        }
        return Map.of("requestId", row.get("request_id"), "action", row.get("action"),
                "commandStatus", row.get("status"), "commandKind", row.get("command_kind"));
    }

    private void lock(String kind, String factory, String requestId) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
                (rs, index) -> Boolean.TRUE, "MES-SHIFT-COMMAND:" + tenant() + ":" + factory + ":" + kind + ":" + requestId);
    }

    private void owner(Map<String, Object> row) {
        require(actor().toString().equals(String.valueOf(row.get("actor_id"))), "仅原操作者可读取或封存此请求");
    }
    private void validate(String kind, String requestId) {
        require(kind != null && Set.of("HANDOVER", "LEFTOVER").contains(kind)
                && requestId != null && requestId.matches("[A-Za-z0-9_-]{16,64}"), "原请求号或命令分类无效");
    }
    private Long tenant() { return TenantContextHolder.getTenantId(); }
    private Long actor() { return SecurityUtils.getUser().getId(); }
    private void require(boolean value, String message) { if (!value) throw new CheckedException(message); }
}
