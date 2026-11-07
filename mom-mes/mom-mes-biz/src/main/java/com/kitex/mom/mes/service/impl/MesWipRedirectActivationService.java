package com.kitex.mom.mes.service.impl;

import static com.kitex.mom.mes.service.impl.MesReceivingTargetRules.*;
import static com.kitex.mom.mes.service.impl.MesWipRedirectReviewCodec.*;

import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.dto.MesWipRedirectActivationDTO.*;
import com.kitex.mom.mes.dto.MesWipRedirectReviewDTO.*;
import com.kitex.mom.mes.entity.MesWipTransferEntity;
import com.kitex.mom.mes.mapper.*;

import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.util.*;
import java.util.function.BooleanSupplier;

@Service
public class MesWipRedirectActivationService {
    private final MesActualFactoryAccessService access;
    private final PermissionService permissions;
    private final MesWipRedirectActivationMapper db;
    private final MesWipTransferMapper locks;
    private final MesWipRedirectTargetService qualification;
    private final MesWipRedirectEffects effects;
    private final BooleanSupplier enabled;

    @Autowired
    public MesWipRedirectActivationService(
            MesActualFactoryAccessService a,
            PermissionService p,
            MesWipRedirectActivationMapper d,
            MesWipTransferMapper l,
            MesWipRedirectTargetService q,
            MesWipRedirectEffects e,
            @Value("${mom.mes.wip-redirect.activation-enabled:false}") boolean enabled) {
        this(a, p, d, l, q, e, () -> enabled);
    }

    MesWipRedirectActivationService(
            MesActualFactoryAccessService a,
            PermissionService p,
            MesWipRedirectActivationMapper d,
            MesWipTransferMapper l,
            MesWipRedirectTargetService q,
            MesWipRedirectEffects e,
            BooleanSupplier enabled) {
        access = a;
        permissions = p;
        db = d;
        locks = l;
        qualification = q;
        effects = e;
        this.enabled = enabled;
    }

    record Scope(Long tenant, String factory, Long actor, String code) {}

    private Scope scope() {
        need(permissions.hasPermission("mes_wipTransfer_view"), "缺少转运查看权限");
        String f = access.require("read");
        var u = SecurityUtils.getUser();
        need(
                u != null
                        && u.getId() != null
                        && u.getId() > 0
                        && Objects.equals(u.getTenantId(), TenantContextHolder.getTenantId()),
                "当前本人租户不一致");
        return new Scope(u.getTenantId(), f, u.getId(), u.getUsername());
    }

    private void ready() {
        need(effects.ready(), "改向生效永久账尚未升级");
    }

    private void owner(Command c, Scope s) {
        command(c);
        need(
                "ACTIVATE".equals(c.action())
                        && "2".equals(c.expectedVersion())
                        && s.tenant.toString().equals(c.tenantId())
                        && s.factory.equals(c.factoryCode())
                        && s.actor.toString().equals(c.actorId()),
                "生效原请求身份或批准版本不一致");
    }

    private void writeAccess(Scope s) {
        need(
                permissions.hasPermission("mes_wipTransfer_redirect_activate")
                        && s.factory.equals(access.require("write")),
                "缺少当前工厂生效办理权限");
    }

    private Proposal eligible(Scope s, String id, MesWipTransferEntity t) {
        var p = effects.original(s.tenant, s.factory, s.actor, id);
        need(
                "APPROVED_AWAITING_ACTIVATION".equals(p.state())
                        && "2".equals(p.version())
                        && t != null
                        && p.transferId().equals(t.getId().toString()),
                "方案不是当前已批准待生效版本");
        need(effects.resolve(t) == null, "原单已生效过改向，后续调整请真实退回后重发");
        current(p.source(), t);
        return p;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Preview preview(String proposal) {
        var s = scope();
        ready();
        writeAccess(s);
        need(enabled.getAsBoolean(), "改向生效当前未启用");
        var p = effects.original(s.tenant, s.factory, s.actor, proposal);
        var t = db.transfer(s.tenant, s.factory, id(p.transferId()));
        p = eligible(s, proposal, t);
        var c = actor(p.originalCheck().selection(), s.actor.toString());
        var checked = qualification.check(c);
        check(checked, c, p.source().originalLocationCode());
        return new Preview(
                SCHEMA,
                s.tenant.toString(),
                s.factory,
                s.actor.toString(),
                proposal,
                "2",
                checked,
                semantic(checked),
                false);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Receipt inspect(Command c) {
        var s = scope();
        owner(c, s);
        ready();
        return receipt(c, s);
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Receipt execute(Command c) {
        var s = scope();
        owner(c, s);
        ready();
        lock(c, s);
        var old = receipt(c, s);
        if (!"NOT_FOUND".equals(old.state())) return old;
        writeAccess(s);
        need(enabled.getAsBoolean(), "改向生效当前未启用");
        var observed = db.transfer(s.tenant, s.factory, id(c.transferId()));
        need(
                observed != null
                        && observed.getSourceReportId() != null
                        && Objects.equals(
                                db.lockReport(s.tenant, s.factory, observed.getSourceReportId()),
                                observed.getSourceReportId()),
                "缺少实际原报工");
        var t = db.lockTransfer(s.tenant, s.factory, id(c.transferId()));
        need(
                t != null && Objects.equals(t.getSourceReportId(), observed.getSourceReportId()),
                "转运来源已变化");
        var p = eligible(s, c.proposalId(), t);
        var choice = actor(p.originalCheck().selection(), s.actor.toString());
        var checked = qualification.check(choice);
        check(checked, choice, p.source().originalLocationCode());
        need(c.reviewHash().equals(semantic(checked)), "生效预览已变化，请重新复核");
        text(s.code, 100);
        var e =
                new Effect(
                        com.kitex.mom.mes.dto.MesWipRedirectActivationDTO.SCHEMA,
                        s.tenant.toString(),
                        s.factory,
                        c.requestId(),
                        c.proposalId(),
                        c.transferId(),
                        t.getRevision().toString(),
                        Long.toString(Math.addExact(t.getRevision(), 1L)),
                        s.actor.toString(),
                        s.code,
                        c.reason(),
                        MesWipRedirectEffects.approvalHash(p),
                        checked,
                        Long.toString(System.currentTimeMillis()));
        need(db.insertRequest(request(c, s, "EXECUTED", null)) == 1, "生效原号保存失败");
        var row = new MesWipRedirectActivationMapper.EffectRow();
        row.setTenantId(s.tenant);
        row.setFactoryCode(s.factory);
        row.setRequestId(c.requestId());
        row.setTransferId(c.transferId());
        row.setEffectJson(write(e));
        row.setEffectHash(sha(row.getEffectJson()));
        row.setReviewJson(semanticText(checked));
        row.setCreatedAt(Long.valueOf(e.createdAt()));
        need(db.insertEffect(row) == 1, "生效原件保存失败");
        need(db.advance(s.tenant, s.factory, t.getId(), t.getRevision()) == 1, "原单已变化，生效已回滚");
        return receipt(c, s);
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Receipt cancel(Cancel v) {
        need(v != null, "缺少原请求");
        var c = v.original();
        var s = scope();
        owner(c, s);
        text(v.reason(), 500);
        ready();
        lock(c, s);
        var old = receipt(c, s);
        if (!"NOT_FOUND".equals(old.state())) return old;
        writeAccess(s);
        need(db.insertRequest(request(c, s, "CANCELED", v.reason())) == 1, "生效原号终止失败");
        return receipt(c, s);
    }

    private void lock(Command c, Scope s) {
        need("read committed".equals(db.isolation()), "生效写入要求READ_COMMITTED");
        locks.lockRequest(
                "MES-WIP-REDIRECT-ACTIVATION:" + s.tenant + ":" + s.factory + ":" + c.requestId());
    }

    private MesWipRedirectReviewMapper.RequestRow request(
            Command c, Scope s, String outcome, String reason) {
        var r = new MesWipRedirectReviewMapper.RequestRow();
        r.setTenantId(s.tenant);
        r.setFactoryCode(s.factory);
        r.setRequestId(c.requestId());
        r.setActorId(s.actor);
        r.setCommandJson(write(c));
        r.setFingerprint(sha(r.getCommandJson()));
        r.setOutcome(outcome);
        r.setCancellationReason(reason);
        r.setCreatedAt(System.currentTimeMillis());
        return r;
    }

    private Receipt receipt(Command c, Scope s) {
        var r = db.request(s.tenant, s.factory, c.requestId());
        String fp = sha(write(c));
        if (r == null)
            return new Receipt(
                    SCHEMA,
                    c.tenantId(),
                    c.factoryCode(),
                    c.actorId(),
                    c.requestId(),
                    fp,
                    "NOT_FOUND",
                    null,
                    null);
        need(
                Objects.equals(r.getTenantId(), s.tenant)
                        && s.factory.equals(r.getFactoryCode())
                        && Objects.equals(r.getActorId(), s.actor)
                        && c.requestId().equals(r.getRequestId())
                        && write(c).equals(r.getCommandJson())
                        && fp.equals(r.getFingerprint()),
                "生效原号身份或内容冲突");
        var e = effects.find(s.tenant, s.factory, c.transferId());
        if ("CANCELED".equals(r.getOutcome())) {
            need(e == null || !e.requestId().equals(c.requestId()), "终止原号仍有生效原件");
            text(r.getCancellationReason(), 500);
            return new Receipt(
                    SCHEMA,
                    c.tenantId(),
                    c.factoryCode(),
                    c.actorId(),
                    c.requestId(),
                    fp,
                    "CANCELED",
                    null,
                    r.getCancellationReason());
        }
        need(
                "EXECUTED".equals(r.getOutcome())
                        && r.getCancellationReason() == null
                        && e != null
                        && e.requestId().equals(c.requestId()),
                "生效原号缺少永久原件");
        return new Receipt(
                SCHEMA,
                c.tenantId(),
                c.factoryCode(),
                c.actorId(),
                c.requestId(),
                fp,
                "COMMITTED",
                MesWipRedirectEffects.event(e),
                null);
    }
}
