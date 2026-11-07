package com.kitex.mom.mes.service.impl;

import static com.kitex.mom.mes.service.impl.MesReceivingTargetRules.*;
import static com.kitex.mom.mes.service.impl.MesWipRedirectReviewCodec.*;

import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.feign.RemoteMdmReceivingLocationService;
import com.kitex.mom.mes.dto.*;
import com.kitex.mom.mes.dto.MesWipDeliverySimulationDTO.*;
import com.kitex.mom.mes.entity.MesWipTransferEntity;
import com.kitex.mom.mes.mapper.MesWipRedirectedDeliveryMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/** 创建时读取真实冻结路线/单位/超报策略；模拟推进不写物理位置或产能预留。 */
@Service
@RequiredArgsConstructor
public class MesWipRedirectedDeliveryService {
    private final MesWipRedirectedDeliveryMapper db;
    private final MesWipRedirectEffects effects;
    private final MesWipNextStationsService next;
    private final RemoteMdmReceivingLocationService mdm;
    private final MesWipRedirectBasisService basis;
    private final MesWipTargetCapacityService capacity;

    boolean ready() {
        int n = db.installed();
        if (n == 0) return false;
        need(n == 1 && Objects.equals(db.ready(), 1), "改向配送升级未就绪");
        return true;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public MesWipRedirectedDeliveryDTO.Target create(MesWipTransferEntity t) {
        need(ready(), "改向配送记录尚未升级");
        var u = SecurityUtils.getUser();
        need(
                u != null
                        && u.getId() != null
                        && u.getId() > 0
                        && Objects.equals(u.getTenantId(), TenantContextHolder.getTenantId())
                        && Objects.equals(t.getTenantId(), u.getTenantId())
                        && "TRANSIT".equals(t.getStatus()),
                "改向配送创建本人或原单不一致");
        var e = effects.resolve(t);
        need(e != null, "原转运尚未改向生效");
        BigDecimal total = BigDecimal.ZERO;
        for (var q :
                Arrays.asList(
                        t.getRemainingQuantity(),
                        t.getReceivedQuantity(),
                        t.getReturnedQuantity(),
                        t.getQuarantinedQuantity(),
                        t.getLostQuantity())) total = total.add(physical(q));
        need(
                total.compareTo(physical(t.getQuantity())) == 0
                        && t.getRemainingQuantity().signum() > 0,
                "改向配送原单数量不守恒");
        String actor = u.getId().toString(),
                tenant = t.getTenantId().toString(),
                factory = t.getFactoryCode();
        var c = e.check().selection();
        Long aid = id(c.arrangeId());
        var v = next.queryTransfer(t.getId(), aid > 1 ? aid - 1 : null);
        need(
                v != null
                        && MesWipNextStationsDTO.TRANSFER_SCHEMA.equals(v.schemaVersion())
                        && tenant.equals(v.tenantId())
                        && factory.equals(v.factoryCode())
                        && actor.equals(v.actorId())
                        && t.getId().toString().equals(v.transferId())
                        && t.getRevision().toString().equals(v.transferRevision())
                        && "TRANSIT".equals(v.status())
                        && t.getSourceStockId().toString().equals(v.sourceStockId())
                        && t.getSourceReportId().toString().equals(v.sourceReportId())
                        && v.routing() != null
                        && "READY".equals(v.routing().availability())
                        && tenant.equals(v.routing().tenantId())
                        && factory.equals(v.routing().factoryCode())
                        && actor.equals(v.routing().actorId()),
                "改向配送冻结后继不可核验");
        var rows =
                v.routing().stations().stream()
                        .filter(a -> c.arrangeId().equals(a.arrangeId()))
                        .toList();
        need(
                rows.size() == 1 && e.check().nodeCode().equals(rows.get(0).nodeCode()),
                "生效目标不是当前冻结直接后继");
        var runtime = resolve(mdm, tenant, factory, actor, rows.get(0), c.worksiteCode());
        var bindings =
                runtime.bindings().stream()
                        .filter(b -> c.locationId().equals(b.locationId()))
                        .toList();
        need(
                bindings.size() == 1
                        && c.bindingRevision().equals(bindings.get(0).revision())
                        && e.check().reference().equals(bindings.get(0).reference()),
                "生效位置关系或原文已变化，请处理原方案后重新办理");
        var policy = basis.verify(t, c.arrangeId());
        var q =
                capacity.observe(
                        t,
                        c.arrangeId(),
                        e.check().nodeCode(),
                        c.worksiteCode(),
                        e.check().reference().station().workshopCode());
        need(
                policy.overReportAllowed() == q.overReportAllowed()
                        && policy.overReportRate().compareTo(new BigDecimal(q.overReportRate()))
                                == 0,
                "创建数量观察与冻结策略不一致");
        var choice =
                MesWipRedirectedDeliveryFacts.choice(
                        e,
                        actor,
                        t.getRevision().toString(),
                        t.getRemainingQuantity().stripTrailingZeros().toPlainString());
        var check =
                new MesWipRedirectTargetDTO.Check(
                        MesWipRedirectTargetDTO.SCHEMA,
                        tenant,
                        factory,
                        actor,
                        choice,
                        e.check().nodeCode(),
                        bindings.get(0).reference(),
                        t.getToLocationCode(),
                        q,
                        "TARGET_CHANGE_CHECKED",
                        Instant.now().toString(),
                        false,
                        false,
                        false);
        check(check, choice, t.getToLocationCode());
        return new MesWipRedirectedDeliveryDTO.Target(
                MesWipRedirectedDeliveryDTO.SCHEMA, e, sha(write(e)), check);
    }

    void verify(Snapshot s, Long tenant, String factory, Long actor) {
        MesWipRedirectedDeliveryFacts.verify(s, tenant.toString(), factory, actor.toString());
        var e = s.redirectedTarget().activation();
        need(e.equals(effects.find(tenant, factory, s.transferId())), "配送冻结的生效原件不属于永久账");
        var source = effects.original(tenant, factory, actor, e.proposalId()).source();
        need(
                s.sourceReportId().equals(source.sourceReportId())
                        && s.sourceStockId().equals(source.sourceStockId())
                        && s.fromLocationCode().equals(source.fromLocationCode())
                        && s.redirectedTarget()
                                .check()
                                .originalLocationCode()
                                .equals(source.originalLocationCode()),
                "配送原发出来源不一致");
    }

    void progress(Snapshot s, MesWipTransferEntity t) {
        need(
                t != null
                        && "TRANSIT".equals(t.getStatus())
                        && Objects.equals(
                                s.transferRevision(), Objects.toString(t.getRevision(), null))
                        && t.getRemainingQuantity() != null
                        && new BigDecimal(s.quantity()).compareTo(t.getRemainingQuantity()) == 0
                        && s.unit().equals(t.getMatUnit()),
                "原单版本、状态或数量已变化，本次模拟仅可取消");
        need(s.redirectedTarget().activation().equals(effects.resolve(t)), "本次模拟生效目标已变化");
    }

    private static BigDecimal physical(BigDecimal q) {
        need(
                q != null && q.signum() >= 0 && q.scale() <= 8 && q.precision() - q.scale() <= 16,
                "实物数量缺失或超出精度");
        return q;
    }
}
