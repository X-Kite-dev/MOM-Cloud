package com.kitex.mom.mes.service.impl;

import static com.kitex.mom.mes.service.impl.MesWipTransferCommandCodec.require;

import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.dto.MesWipTransferRequestDTO;
import com.kitex.mom.mes.dto.MesWipTransferRequestDTO.*;
import com.kitex.mom.mes.mapper.MesWipTransferMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.util.*;

@Service
@RequiredArgsConstructor
public class MesWipTransferRequestService {
    static final List<String> KINDS =
            List.of(
                    "dispatch",
                    "receive",
                    "request_return",
                    "receive_return",
                    "receive_quarantine",
                    "hold",
                    "resume");
    private final MesActualFactoryAccessService access;
    private final MesWipTransferRequestStore store;
    private final MesWipTransferMapper transfers;
    private final MesWipTransferService service;
    private final PermissionService permissions;

    private void permission(String kind) {
        if (!KINDS.contains(kind) || !permissions.hasPermission("mes_wipTransfer_" + kind))
            throw new AccessDeniedException("没有此转运操作的权限");
    }

    @Transactional(readOnly = true)
    public Context context() {
        String f = access.require("read");
        var allowed =
                KINDS.stream()
                        .filter(k -> permissions.hasPermission("mes_wipTransfer_" + k))
                        .toList();
        if (allowed.isEmpty()) throw new AccessDeniedException("没有转运操作权限");
        return new Context(
                MesWipTransferRequestDTO.SCHEMA,
                TenantContextHolder.getTenantId().toString(),
                f,
                SecurityUtils.getUser().getId().toString(),
                allowed,
                store.installed(),
                canWrite());
    }

    private boolean canWrite() {
        try {
            access.require("write");
            return true;
        } catch (RuntimeException denied) {
            return false;
        }
    }

    private Envelope checked(String kind, Envelope e, boolean write) {
        permission(kind);
        String f = access.require(write ? "write" : "read");
        require(
                e != null
                        && (e.command() != null && e.command().getDispatchTarget() != null
                                        ? MesWipTransferRequestDTO.BOUND_SCHEMA
                                        : MesWipTransferRequestDTO.SCHEMA)
                                .equals(e.schemaVersion())
                        && Objects.equals(
                                e.tenantId(), TenantContextHolder.getTenantId().toString())
                        && Objects.equals(e.factoryCode(), f)
                        && Objects.equals(e.actorId(), SecurityUtils.getUser().getId().toString())
                        && e.command() != null
                        && Objects.equals(
                                e.command().getOperationType(), kind.toUpperCase(Locale.ROOT)),
                "转运原请求的实名、工厂或操作类型不一致");
        MesWipTransferCommandCodec.normalize(e.command());
        return e;
    }

    private void lock(Envelope e) {
        transfers.lockRequest(
                "MES-WIP-TRANSFER:"
                        + e.tenantId()
                        + ":"
                        + e.factoryCode()
                        + ":"
                        + e.command().getRequestId());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Receipt inspect(String kind, Envelope e) {
        checked(kind, e, false);
        return store.inspect(e, store.installed());
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Receipt execute(String kind, Envelope e) {
        checked(kind, e, true);
        store.ready();
        lock(e);
        var found = store.inspect(e, true);
        require(!"CANCELED".equals(found.state()), "此原请求已永久终止，禁止继续执行");
        if ("COMMITTED".equals(found.state())) return found;
        service.execute(e.command());
        var r = store.inspect(e, true);
        require("COMMITTED".equals(r.state()), "交接缺少同事务原回执，整笔回滚");
        return r;
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Receipt cancel(String kind, Cancel cancel) {
        require(cancel != null, "请提供原请求及终止原因");
        var e = checked(kind, cancel.original(), true);
        require(
                cancel.reason() != null
                        && !cancel.reason().isBlank()
                        && cancel.reason().length() <= 500
                        && cancel.reason().equals(cancel.reason().trim()),
                "请填写500字以内原请求终止原因");
        store.ready();
        lock(e);
        var r = store.inspect(e, true);
        if ("COMMITTED".equals(r.state())) return r;
        if ("CANCELED".equals(r.state())) {
            require(Objects.equals(r.cancellation().reason(), cancel.reason()), "终止原请求不能改写首次原因");
            return r;
        }
        store.cancel(e, cancel.reason());
        var saved = store.inspect(e, true);
        require("CANCELED".equals(saved.state()), "终止原请求缺少持久回执");
        return saved;
    }
}
