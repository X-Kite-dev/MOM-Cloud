package com.kitex.mom.mes.service.impl;

import static com.kitex.mom.mes.service.impl.MesReceivingTargetRules.*;
import static com.kitex.mom.mes.service.impl.MesWipRedirectReviewCodec.*;

import com.kitex.mom.mes.dto.*;
import com.kitex.mom.mes.dto.MesWipDeliverySimulationDTO.Snapshot;
import com.kitex.mom.mes.dto.MesWipRedirectTargetDTO.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

final class MesWipRedirectedDeliveryFacts {
    private MesWipRedirectedDeliveryFacts() {}

    static Choice choice(
            MesWipRedirectActivationDTO.Effect e, String actor, String revision, String quantity) {
        var c = e.check().selection();
        return new Choice(
                c.schemaVersion(),
                c.tenantId(),
                c.factoryCode(),
                actor,
                c.transferId(),
                revision,
                c.arrangeId(),
                c.worksiteCode(),
                c.locationId(),
                c.bindingRevision(),
                c.referenceHash(),
                quantity,
                c.unit());
    }

    static void verify(Snapshot s, String tenant, String factory, String actor) {
        var r = s.redirectedTarget();
        need(
                r != null
                        && MesWipRedirectedDeliveryDTO.SCHEMA.equals(r.schemaVersion())
                        && s.creationCheck() == null,
                "改向配送原件或类型不一致");
        var e = r.activation();
        need(
                e != null
                        && MesWipRedirectActivationDTO.SCHEMA.equals(e.schemaVersion())
                        && tenant.equals(e.tenantId())
                        && factory.equals(e.factoryCode())
                        && s.transferId().equals(e.transferId())
                        && sha(write(e)).equals(r.activationHash()),
                "改向配送生效原件不一致");
        id(s.transferId());
        id(s.sourceStockId());
        id(s.sourceReportId());
        id(actor);
        id(e.actorId());
        id(e.createdAt());
        key(e.requestId());
        key(e.proposalId());
        need(
                id(e.afterRevision()) == Math.addExact(id(e.beforeRevision()), 1L)
                        && id(s.transferRevision()) >= id(e.afterRevision()),
                "改向配送版本不一致");
        var c = r.check();
        check(
                c,
                choice(e, actor, s.transferRevision(), s.quantity()),
                e.check().originalLocationCode());
        need(
                c.nodeCode().equals(e.check().nodeCode())
                        && c.reference().equals(e.check().reference())
                        && s.unit().equals(c.selection().unit())
                        && s.toLocationCode().equals(c.reference().point().code())
                        && !s.fromLocationCode().equals(s.toLocationCode())
                        && new BigDecimal(s.quantity())
                                        .compareTo(new BigDecimal(e.check().selection().quantity()))
                                <= 0,
                "改向配送目标或在途数量不一致");
        var at = Instant.parse(c.observedAt());
        need(
                !at.isBefore(Instant.parse(e.check().observedAt()))
                        && at.toEpochMilli() >= id(e.createdAt()),
                "创建复核早于生效原件");
    }

    static void time(MesWipRedirectedDeliveryDTO.Target r, Long created) {
        need(
                created != null
                        && created > 0
                        && Instant.parse(r.check().observedAt()).toEpochMilli() <= created,
                "改向创建复核晚于首事件");
    }
}
