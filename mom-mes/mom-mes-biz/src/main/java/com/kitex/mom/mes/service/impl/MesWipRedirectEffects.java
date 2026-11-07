package com.kitex.mom.mes.service.impl;

import static com.kitex.mom.mes.service.impl.MesReceivingTargetRules.*;
import static com.kitex.mom.mes.service.impl.MesWipRedirectReviewCodec.*;

import com.kitex.mom.mes.dto.MesWipRedirectActivationDTO.*;
import com.kitex.mom.mes.dto.MesWipRedirectReviewDTO.*;
import com.kitex.mom.mes.entity.MesWipTransferEntity;
import com.kitex.mom.mes.mapper.*;

import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.BooleanSupplier;

/** 已生效的接收目标事实；关闭新办开关不得回退事实，原发出目标永不改写。 */
@Service
public class MesWipRedirectEffects {
    private final MesWipRedirectActivationMapper db;
    private final MesWipRedirectReviewMapper reviews;
    private final BooleanSupplier enabled;

    @Autowired
    public MesWipRedirectEffects(
            MesWipRedirectActivationMapper db,
            MesWipRedirectReviewMapper reviews,
            @Value("${mom.mes.wip-redirect.activation-enabled:false}") boolean enabled) {
        this(db, reviews, () -> enabled);
    }

    MesWipRedirectEffects(
            MesWipRedirectActivationMapper db,
            MesWipRedirectReviewMapper reviews,
            BooleanSupplier enabled) {
        this.db = db;
        this.reviews = reviews;
        this.enabled = enabled;
    }

    boolean enabled() {
        return enabled.getAsBoolean();
    }

    boolean ready() {
        int n = db.installed();
        if (n == 0) return false;
        need(n == 2 && Objects.equals(db.ready(), 1), "改向生效账部分安装或守卫未就绪");
        return true;
    }

    static String approvalHash(Proposal p) {
        return sha(
                write(Arrays.asList(p.source(), p.originalCheck(), p.approvedCheck(), p.events())));
    }

    Proposal original(Long tenant, String factory, Long actor, String proposal) {
        return MesWipRedirectReviewService.ledger(reviews, tenant, factory, actor, proposal);
    }

    Effect find(Long tenant, String factory, String transfer) {
        if (!ready()) return null;
        var row = db.effect(tenant, factory, transfer);
        if (row == null) return null;
        need(
                Objects.equals(row.getTenantId(), tenant)
                        && factory.equals(row.getFactoryCode())
                        && transfer.equals(row.getTransferId())
                        && sha(row.getEffectJson()).equals(row.getEffectHash()),
                "生效原件范围或摘要不一致");
        var e = parse(row.getEffectJson(), Effect.class);
        need(
                com.kitex.mom.mes.dto.MesWipRedirectActivationDTO.SCHEMA.equals(e.schemaVersion())
                        && tenant.toString().equals(e.tenantId())
                        && factory.equals(e.factoryCode())
                        && transfer.equals(e.transferId())
                        && row.getRequestId().equals(e.requestId())
                        && Objects.equals(row.getCreatedAt().toString(), e.createdAt()),
                "生效原件身份不一致");
        need(Objects.equals(row.getReviewJson(), semanticText(e.check())), "生效预览原件损坏");
        id(e.actorId());
        text(e.actorCode(), 100);
        text(e.reason(), 500);
        id(e.createdAt());
        var r = db.request(tenant, factory, e.requestId());
        need(
                r != null
                        && Objects.equals(r.getTenantId(), tenant)
                        && factory.equals(r.getFactoryCode())
                        && e.requestId().equals(r.getRequestId())
                        && "EXECUTED".equals(r.getOutcome())
                        && r.getCancellationReason() == null
                        && Objects.equals(r.getActorId().toString(), e.actorId())
                        && sha(r.getCommandJson()).equals(r.getFingerprint()),
                "生效原号不完整");
        var c = parse(r.getCommandJson(), Command.class);
        command(c);
        need(
                "ACTIVATE".equals(c.action())
                        && e.tenantId().equals(c.tenantId())
                        && e.factoryCode().equals(c.factoryCode())
                        && e.actorId().equals(c.actorId())
                        && e.requestId().equals(c.requestId())
                        && e.transferId().equals(c.transferId())
                        && e.proposalId().equals(c.proposalId())
                        && "2".equals(c.expectedVersion())
                        && e.reason().equals(c.reason())
                        && c.reviewHash().equals(semantic(e.check())),
                "生效原请求与取证不一致");
        var p = original(tenant, factory, Long.valueOf(e.actorId()), e.proposalId());
        need(
                "APPROVED_AWAITING_ACTIVATION".equals(p.state())
                        && "2".equals(p.version())
                        && transfer.equals(p.transferId())
                        && approvalHash(p).equals(e.approvalHash())
                        && p.source().transferRevision().equals(e.beforeRevision())
                        && Long.parseLong(e.afterRevision())
                                == Math.addExact(id(e.beforeRevision()), 1L),
                "生效的批准链或原版本不一致");
        check(
                e.check(),
                actor(p.originalCheck().selection(), e.actorId()),
                p.source().originalLocationCode());
        return e;
    }

    Effect resolve(MesWipTransferEntity t) {
        need(
                t != null
                        && t.getTenantId() != null
                        && t.getFactoryCode() != null
                        && t.getId() != null,
                "缺少当前转运身份");
        var e = find(t.getTenantId(), t.getFactoryCode(), t.getId().toString());
        if (e == null) return null;
        var p =
                original(
                        t.getTenantId(),
                        t.getFactoryCode(),
                        Long.valueOf(e.actorId()),
                        e.proposalId());
        var s = p.source();
        need(
                t.getRevision() != null
                        && t.getRevision() >= id(e.afterRevision())
                        && Objects.equals(t.getSourceReportId().toString(), s.sourceReportId())
                        && Objects.equals(t.getSourceStockId().toString(), s.sourceStockId())
                        && Objects.equals(t.getFromLocationCode(), s.fromLocationCode())
                        && Objects.equals(t.getToLocationCode(), s.originalLocationCode())
                        && Objects.equals(t.getMatUnit(), s.unit())
                        && sha(write(Arrays.asList(t.getSourceSnapshot(), t.getLocationSnapshot())))
                                .equals(s.sourceHash())
                        && t.getRemainingQuantity() != null
                        && t.getRemainingQuantity().signum() >= 0
                        && t.getRemainingQuantity()
                                        .compareTo(new java.math.BigDecimal(s.quantity()))
                                <= 0,
                "生效后原转运来源、目标或数量被改写");
        return e;
    }

    public String receivingTarget(MesWipTransferEntity t, boolean returning) {
        var e = resolve(t);
        return returning
                ? t.getFromLocationCode()
                : e == null ? t.getToLocationCode() : e.check().reference().point().code();
    }

    public void requireOriginalTarget(MesWipTransferEntity t) {
        need(resolve(t) == null, "接收目标已改向生效，原目标核对/配送模拟不能继续；请按生效目标实际交接");
    }

    java.util.List<Display> displays(java.util.List<MesWipTransferEntity> rows) {
        need(rows != null && rows.size() <= 30, "转运列表超出页面范围");
        if (rows.isEmpty()) return List.of();
        if (!ready())
            return rows.stream().map(t -> new Display(t, t.getToLocationCode(), null)).toList();
        var first = rows.get(0);
        for (var t : rows)
            need(
                    Objects.equals(first.getTenantId(), t.getTenantId())
                            && Objects.equals(first.getFactoryCode(), t.getFactoryCode()),
                    "转运页面实际工厂不一致");
        var active =
                db.effects(
                        first.getTenantId(),
                        first.getFactoryCode(),
                        rows.stream().map(t -> t.getId().toString()).toList());
        need(active != null && active.size() <= rows.size(), "生效列表超出范围");
        var ids = new HashSet<String>();
        for (var row : active)
            need(
                    Objects.equals(first.getTenantId(), row.getTenantId())
                            && Objects.equals(first.getFactoryCode(), row.getFactoryCode())
                            && rows.stream()
                                    .anyMatch(t -> t.getId().toString().equals(row.getTransferId()))
                            && ids.add(row.getTransferId()),
                    "生效列表范围或唯一性不一致");
        return rows.stream()
                .map(
                        t ->
                                ids.contains(t.getId().toString())
                                        ? display(t)
                                        : new Display(t, t.getToLocationCode(), null))
                .toList();
    }

    Display display(MesWipTransferEntity t) {
        var e = resolve(t);
        return new Display(
                t, e == null ? t.getToLocationCode() : e.check().reference().point().code(), e);
    }

    Proposal project(Proposal p, MesWipTransferEntity t) {
        var e = find(Long.valueOf(p.tenantId()), p.factoryCode(), p.transferId());
        boolean same = e != null && e.proposalId().equals(p.proposalId());
        var events = new ArrayList<>(p.events());
        if (same) events.add(event(e));
        return new Proposal(
                p.schemaVersion(),
                p.tenantId(),
                p.factoryCode(),
                p.actorId(),
                p.proposalId(),
                p.transferId(),
                same ? "3" : p.version(),
                same ? "EFFECTIVE" : p.state(),
                p.applicantId(),
                p.applicantCode(),
                p.source(),
                p.originalCheck(),
                p.approvedCheck(),
                List.copyOf(events),
                !matchesSource(p.source(), t),
                same,
                same ? e : null);
    }

    static Event event(Effect e) {
        return new Event(
                e.requestId(),
                e.proposalId(),
                "3",
                "ACTIVATE",
                "EFFECTIVE",
                e.actorId(),
                e.actorCode(),
                e.reason(),
                e.createdAt());
    }
}
