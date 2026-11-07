package com.kitex.mom.mes.service.impl;

import static com.kitex.mom.mes.service.impl.MesReceivingTargetRules.*;
import static com.kitex.mom.mes.service.impl.MesWipRedirectReviewCodec.*;

import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.dto.MesWipRedirectReviewDTO.*;
import com.kitex.mom.mes.dto.MesWipRedirectTargetDTO.Check;
import com.kitex.mom.mes.entity.MesWipTransferEntity;
import com.kitex.mom.mes.mapper.*;
import com.kitex.mom.mes.mapper.MesWipRedirectReviewMapper.*;

import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.util.*;
import java.util.function.BooleanSupplier;

/** 审核只追加意图，不写原转运目标、库存或实际位置。 */
@Service
public class MesWipRedirectReviewService {
    private final MesActualFactoryAccessService access;
    private final PermissionService permissions;
    private final MesWipRedirectReviewMapper db;
    private final MesWipTransferMapper locks;
    private final MesWipRedirectTargetService qualification;
    private final BooleanSupplier enabled;
    private final MesWipRedirectEffects effects;

    @Autowired
    public MesWipRedirectReviewService(
            MesActualFactoryAccessService a,
            PermissionService p,
            MesWipRedirectReviewMapper d,
            MesWipTransferMapper l,
            MesWipRedirectTargetService q,
            @Value("${mom.mes.wip-redirect.review-enabled:false}") boolean e,
            MesWipRedirectEffects effects) {
        this(a, p, d, l, q, () -> e, effects);
    }

    MesWipRedirectReviewService(
            MesActualFactoryAccessService a,
            PermissionService p,
            MesWipRedirectReviewMapper d,
            MesWipTransferMapper l,
            MesWipRedirectTargetService q,
            BooleanSupplier e,
            MesWipRedirectEffects effects) {
        access = a;
        permissions = p;
        db = d;
        locks = l;
        qualification = q;
        enabled = e;
        this.effects = effects;
    }

    private record Scope(Long tenant, String factory, Long actor, String actorCode) {}

    private Scope scope() {
        need(permissions.hasPermission("mes_wipTransfer_view"), "没有转运查看权限");
        String f = access.require("read");
        var u = SecurityUtils.getUser();
        need(
                u != null
                        && u.getId() != null
                        && u.getId() > 0
                        && Objects.equals(u.getTenantId(), TenantContextHolder.getTenantId()),
                "实际本人租户不一致");
        return new Scope(u.getTenantId(), f, u.getId(), u.getUsername());
    }

    private boolean ready() {
        int n = db.installed();
        if (n == 0) return false;
        need(n == 2 && Objects.equals(db.ready(), 1), "改向审批账部分安装或守卫未就绪");
        return true;
    }

    private void requireReady() {
        need(ready(), "改向审批永久账尚未升级");
    }

    private void owner(Command c, Scope s) {
        command(c);
        need(
                s.tenant.toString().equals(c.tenantId())
                        && s.factory.equals(c.factoryCode())
                        && s.actor.toString().equals(c.actorId()),
                "原请求本人、租户或工厂不一致");
    }

    private String permission(String action) {
        return Set.of("SUBMIT", "WITHDRAW").contains(action)
                ? "mes_wipTransfer_redirect_apply"
                : "mes_wipTransfer_redirect_review";
    }

    private void writeAccess(Scope s, String action) {
        need(permissions.hasPermission(permission(action)), "缺少本次改向申请/审核权限");
        need(s.factory.equals(access.require("write")), "实际工厂写入范围已变化");
    }

    private MesWipTransferEntity transfer(Scope s, String id, boolean lock) {
        var t =
                lock
                        ? db.lockTransfer(s.tenant, s.factory, MesReceivingTargetRules.id(id))
                        : db.transfer(s.tenant, s.factory, MesReceivingTargetRules.id(id));
        need(
                t != null
                        && id.equals(Objects.toString(t.getId()))
                        && s.tenant.equals(t.getTenantId())
                        && s.factory.equals(t.getFactoryCode())
                        && "0".equals(t.getDelFlag()),
                "实际工厂没有该转运单");
        return t;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Overview overview(String transferId) {
        var s = scope();
        var t = transfer(s, transferId, false);
        boolean r = ready(), w;
        try {
            w = s.factory.equals(access.require("write"));
        } catch (RuntimeException denied) {
            w = false;
        }
        var ids = r ? db.proposals(s.tenant, s.factory, transferId) : List.<String>of();
        need(ids != null && ids.size() <= 21, "审批列表超出范围");
        var views = new ArrayList<Proposal>();
        for (String id : ids.subList(0, Math.min(20, ids.size()))) views.add(load(s, id));
        return new Overview(
                SCHEMA,
                s.tenant.toString(),
                s.factory,
                s.actor.toString(),
                transferId,
                r,
                enabled.getAsBoolean(),
                w,
                permissions.hasPermission("mes_wipTransfer_redirect_apply"),
                permissions.hasPermission("mes_wipTransfer_redirect_review"),
                List.copyOf(views),
                ids.size() > 20,
                permissions.hasPermission("mes_wipTransfer_redirect_activate"),
                effects.enabled(),
                effects.resolve(t));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Proposal proposal(String id) {
        return load(scope(), id);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Preview preview(String id) {
        var s = scope();
        need(permissions.hasPermission("mes_wipTransfer_redirect_review"), "没有独立审核权限");
        var p = load(s, id);
        next(p.state(), "APPROVE", p.applicantId(), s.actor.toString());
        var t = transfer(s, p.transferId(), false);
        current(p.source(), t);
        var choice = actor(p.originalCheck().selection(), s.actor.toString());
        var checked = qualification.check(choice);
        check(checked, choice, p.source().originalLocationCode());
        return new Preview(
                SCHEMA,
                s.tenant.toString(),
                s.factory,
                s.actor.toString(),
                id,
                p.version(),
                checked,
                semantic(checked),
                false);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Receipt inspect(Command c) {
        var s = scope();
        owner(c, s);
        requireReady();
        return receipt(s, c);
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Receipt execute(Command c) {
        var s = scope();
        owner(c, s);
        need(!"ACTIVATE".equals(c.action()), "生效须由专用办理服务执行");
        requireReady();
        need("read committed".equals(db.isolation()), "审批写入要求READ_COMMITTED");
        locks.lockRequest(
                "MES-WIP-REDIRECT-REVIEW:" + s.tenant + ":" + s.factory + ":" + c.requestId());
        var old = receipt(s, c);
        if (!"NOT_FOUND".equals(old.state())) return old;
        writeAccess(s, c.action());
        need(enabled.getAsBoolean(), "当前运行环境未启用改向审核");
        var observed = transfer(s, c.transferId(), false);
        need(
                observed.getSourceReportId() != null
                        && Objects.equals(
                                db.lockReport(s.tenant, s.factory, observed.getSourceReportId()),
                                observed.getSourceReportId()),
                "原报工已失效");
        var t = transfer(s, c.transferId(), true);
        need(Objects.equals(observed.getSourceReportId(), t.getSourceReportId()), "原报工来源已变化");
        Source frozen = null;
        Check checked = null;
        String before = "NONE", applicant = s.actor.toString();
        if ("SUBMIT".equals(c.action())) {
            need("TRANSIT".equals(t.getStatus()), "仅正向在途可申请");
            need(effects.resolve(t) == null, "原单已生效过改向，后续调整请真实退回后重发");
            frozen = source(t);
            checked = qualification.check(c.selection());
            check(checked, c.selection(), frozen.originalLocationCode());
            need(
                    frozen.transferRevision().equals(c.selection().transferRevision())
                            && frozen.quantity().equals(c.selection().quantity())
                            && frozen.unit().equals(c.selection().unit()),
                    "申请选择已不是当前原单");
        } else {
            var p = load(s, c.proposalId());
            need(
                    c.transferId().equals(p.transferId())
                            && c.expectedVersion().equals(p.version()),
                    "申请版本已变化");
            before = p.state();
            applicant = p.applicantId();
            next(before, c.action(), applicant, s.actor.toString());
            if ("APPROVE".equals(c.action())) {
                current(p.source(), t);
                var choice = actor(p.originalCheck().selection(), s.actor.toString());
                checked = qualification.check(choice);
                check(checked, choice, p.source().originalLocationCode());
                need(c.reviewHash().equals(semantic(checked)), "审核预览的目标、单位或数量已变化，请重新预览");
            }
        }
        String after = next(before, c.action(), applicant, s.actor.toString());
        var request = request(s, c, "EXECUTED", null);
        need(db.insertRequest(request) == 1, "审批原请求保存失败");
        var e = new EventRow();
        e.setTenantId(s.tenant);
        e.setFactoryCode(s.factory);
        e.setRequestId(c.requestId());
        e.setProposalId(c.proposalId());
        e.setTransferId(c.transferId());
        e.setSequenceNo(Integer.parseInt(c.expectedVersion()) + 1);
        e.setActorId(s.actor);
        text(s.actorCode, 100);
        e.setActorCode(s.actorCode);
        e.setAction(c.action());
        e.setBeforeState(before);
        e.setAfterState(after);
        e.setEvidenceJson(write(new Evidence(frozen, checked)));
        e.setEvidenceHash(sha(e.getEvidenceJson()));
        e.setReviewJson("APPROVE".equals(c.action()) ? semanticText(checked) : null);
        e.setCreatedAt(System.currentTimeMillis());
        need(db.insertEvent(e) == 1, "审批事件保存失败");
        return receipt(s, c);
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Receipt cancel(Cancel cancel) {
        var s = scope();
        need(cancel != null, "缺少原请求");
        var c = cancel.original();
        owner(c, s);
        text(cancel.reason(), 500);
        requireReady();
        need("read committed".equals(db.isolation()), "终止原号要求READ_COMMITTED");
        locks.lockRequest(
                "MES-WIP-REDIRECT-REVIEW:" + s.tenant + ":" + s.factory + ":" + c.requestId());
        var old = receipt(s, c);
        if (!"NOT_FOUND".equals(old.state())) return old;
        writeAccess(s, c.action());
        need(db.insertRequest(request(s, c, "CANCELED", cancel.reason())) == 1, "原号终止保存失败");
        return receipt(s, c);
    }

    private RequestRow request(Scope s, Command c, String outcome, String reason) {
        var r = new RequestRow();
        r.setTenantId(s.tenant);
        r.setFactoryCode(s.factory);
        r.setActorId(s.actor);
        r.setRequestId(c.requestId());
        r.setCommandJson(write(c));
        r.setFingerprint(sha(r.getCommandJson()));
        r.setOutcome(outcome);
        r.setCancellationReason(reason);
        r.setCreatedAt(System.currentTimeMillis());
        return r;
    }

    private Receipt receipt(Scope s, Command c) {
        var row = db.request(s.tenant, s.factory, c.requestId());
        String hash = sha(write(c));
        if (row == null)
            return new Receipt(
                    SCHEMA,
                    s.tenant.toString(),
                    s.factory,
                    s.actor.toString(),
                    c.requestId(),
                    hash,
                    "NOT_FOUND",
                    null,
                    null);
        need(
                Objects.equals(row.getTenantId(), s.tenant)
                        && s.factory.equals(row.getFactoryCode())
                        && Objects.equals(row.getActorId(), s.actor)
                        && c.requestId().equals(row.getRequestId())
                        && write(c).equals(row.getCommandJson())
                        && hash.equals(row.getFingerprint()),
                "原号身份或内容冲突");
        var event = db.event(s.tenant, s.factory, c.requestId());
        if ("CANCELED".equals(row.getOutcome())) {
            need(event == null, "终止原号仍有关联事件");
            text(row.getCancellationReason(), 500);
            return new Receipt(
                    SCHEMA,
                    s.tenant.toString(),
                    s.factory,
                    s.actor.toString(),
                    c.requestId(),
                    hash,
                    "CANCELED",
                    null,
                    row.getCancellationReason());
        }
        need(
                "EXECUTED".equals(row.getOutcome())
                        && event != null
                        && row.getCancellationReason() == null,
                "原号结局不完整");
        var proposal = load(s, event.getProposalId(), false);
        var matches =
                proposal.events().stream()
                        .filter(e -> e.requestId().equals(c.requestId()))
                        .toList();
        need(matches.size() == 1, "原号事件不唯一");
        return new Receipt(
                SCHEMA,
                s.tenant.toString(),
                s.factory,
                s.actor.toString(),
                c.requestId(),
                hash,
                "COMMITTED",
                matches.get(0),
                null);
    }

    private Proposal load(Scope s, String id) {
        return load(s, id, true);
    }

    private Proposal load(Scope s, String id, boolean hydrateCurrent) {
        key(id);
        requireReady();
        var p = ledger(db, s.tenant, s.factory, s.actor, id);
        if (!hydrateCurrent) return p;
        var t = db.transfer(s.tenant, s.factory, MesReceivingTargetRules.id(p.transferId()));
        return effects.project(p, t);
    }

    static Proposal ledger(
            MesWipRedirectReviewMapper db, Long tenant, String factory, Long actor, String id) {
        key(id);
        var rows = db.events(tenant, factory, id);
        need(rows != null && !rows.isEmpty() && rows.size() <= 3, "审批链缺失或超出范围");
        String state = "NONE", applicant = null, applicantCode = null, transfer = null;
        Source source = null;
        Check original = null, approved = null;
        var events = new ArrayList<Event>();
        int seq = 0;
        for (var row : rows) {
            need(
                    Objects.equals(row.getTenantId(), tenant)
                            && factory.equals(row.getFactoryCode())
                            && id.equals(row.getProposalId())
                            && Objects.equals(row.getSequenceNo(), ++seq)
                            && row.getCreatedAt() != null
                            && row.getCreatedAt() > 0,
                    "审批事件范围或序号不一致");
            var request = db.request(tenant, factory, row.getRequestId());
            need(
                    request != null
                            && "EXECUTED".equals(request.getOutcome())
                            && request.getCancellationReason() == null
                            && row.getActorId().equals(request.getActorId())
                            && sha(request.getCommandJson()).equals(request.getFingerprint()),
                    "审批原请求缺失或损坏");
            var c = parse(request.getCommandJson(), Command.class);
            command(c);
            need(
                    c.tenantId().equals(tenant.toString())
                            && c.factoryCode().equals(factory)
                            && c.actorId().equals(row.getActorId().toString())
                            && c.requestId().equals(row.getRequestId())
                            && c.proposalId().equals(id)
                            && c.action().equals(row.getAction())
                            && c.expectedVersion().equals(Integer.toString(seq - 1))
                            && Objects.equals(row.getTransferId(), c.transferId())
                            && state.equals(row.getBeforeState()),
                    "审批原文与事件身份不一致");
            need(sha(row.getEvidenceJson()).equals(row.getEvidenceHash()), "审批资格原件指纹不一致");
            var evidence = parse(row.getEvidenceJson(), Evidence.class);
            if (seq == 1) {
                need(
                        "SUBMIT".equals(c.action())
                                && id.equals(c.requestId())
                                && evidence.source() != null,
                        "申请首事件不完整");
                source = evidence.source();
                applicant = c.actorId();
                applicantCode = row.getActorCode();
                transfer = c.transferId();
                need(
                        transfer.equals(source.transferId())
                                && source.transferRevision()
                                        .equals(c.selection().transferRevision())
                                && source.quantity().equals(c.selection().quantity())
                                && source.unit().equals(c.selection().unit())
                                && source.sourceHash() != null
                                && source.sourceHash().matches("[0-9a-f]{64}")
                                && !source.fromLocationCode().equals(source.originalLocationCode()),
                        "申请原单快照不一致");
                id(source.sourceReportId());
                id(source.sourceStockId());
                check(evidence.check(), c.selection(), source.originalLocationCode());
                original = evidence.check();
            } else {
                need(transfer.equals(c.transferId()) && evidence.source() == null, "审核改写了申请来源");
                if ("APPROVE".equals(c.action())) {
                    var selection = actor(original.selection(), c.actorId());
                    check(evidence.check(), selection, source.originalLocationCode());
                    need(
                            Objects.equals(row.getReviewJson(), semanticText(evidence.check()))
                                    && c.reviewHash().equals(semantic(evidence.check())),
                            "批准原件与审核预览不一致");
                    approved = evidence.check();
                } else need(evidence.check() == null && row.getReviewJson() == null, "非批准事件附带了新资格");
            }
            state = next(state, c.action(), applicant, c.actorId());
            need(state.equals(row.getAfterState()), "审批结果被改写");
            text(row.getActorCode(), 100);
            events.add(
                    new Event(
                            c.requestId(),
                            id,
                            Integer.toString(seq),
                            c.action(),
                            state,
                            c.actorId(),
                            row.getActorCode(),
                            c.reason(),
                            row.getCreatedAt().toString()));
        }
        return new Proposal(
                SCHEMA,
                tenant.toString(),
                factory,
                actor.toString(),
                id,
                transfer,
                Integer.toString(seq),
                state,
                applicant,
                applicantCode,
                source,
                original,
                approved,
                List.copyOf(events),
                true,
                false,
                null);
    }
}
