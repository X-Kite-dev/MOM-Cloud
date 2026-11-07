package com.kitex.mom.mes.service.impl;

import static com.kitex.mom.mes.service.impl.MesReceivingTargetRules.*;
import static com.kitex.mom.mes.service.impl.MesWipQuantityRules.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.dto.*;
import com.kitex.mom.mes.dto.MesWipDeliverySimulationDTO.*;
import com.kitex.mom.mes.entity.*;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/** 模拟创建时观察，不预留来料/工位产能，不代替开工、质量或实物交接。 */
@Service
@RequiredArgsConstructor
public class MesWipDeliveryCreationCheckService {
    static final String SCHEMA = "MES_WIP_DELIVERY_CREATION_CHECK@1";
    private final MesActualFactoryAccessService access;
    private final MesDeliveryTargetService targets;
    private final MesWipTargetCapacityService capacity;

    @Transactional(propagation = Propagation.MANDATORY)
    public CreationCheck check(MesWipTransferEntity transfer, RouteTarget original) {
        Long tenant = TenantContextHolder.getTenantId();
        String factory = access.require("read");
        var user = SecurityUtils.getUser();
        need(
                tenant != null
                        && user != null
                        && user.getId() != null
                        && user.getId() > 0
                        && tenant.equals(user.getTenantId())
                        && transfer != null
                        && tenant.equals(transfer.getTenantId())
                        && factory.equals(transfer.getFactoryCode())
                        && "TRANSIT".equals(transfer.getStatus()),
                "配送复核必须使用实际本人及当前工厂在途原单");
        need(original != null, "绑定配送缺少原目标");
        MesWipDeliveryBoundOrigin.verify(
                original,
                tenant.toString(),
                factory,
                Objects.toString(transfer.getSourceStockId(), null),
                Objects.toString(transfer.getSourceReportId(), null),
                transfer.getToLocationCode());
        var s = original.selection();
        var choice =
                new MesDeliveryTargetDTO.Choice(
                        MesDeliveryTargetDTO.SCHEMA,
                        tenant.toString(),
                        factory,
                        user.getId().toString(),
                        transfer.getId().toString(),
                        transfer.getRevision().toString(),
                        s.getArrangeId(),
                        s.getWorksiteCode(),
                        s.getLocationId(),
                        s.getBindingRevision(),
                        s.getReferenceHash());
        var match = targets.check(choice);
        need(
                match != null
                        && MesDeliveryTargetDTO.SCHEMA.equals(match.schemaVersion())
                        && tenant.toString().equals(match.tenantId())
                        && factory.equals(match.factoryCode())
                        && user.getId().toString().equals(match.actorId())
                        && choice.equals(match.selection())
                        && "TARGET_MATCHED".equals(match.state())
                        && !match.productionRelease(),
                "配送目标当前位置关系未通过复核");
        Instant.parse(match.observedAt());
        var q =
                capacity.observe(
                        transfer,
                        s.getArrangeId(),
                        s.getNodeCode(),
                        s.getWorksiteCode(),
                        original.reference().station().workshopCode());
        var observedAt = Instant.now();
        need(!observedAt.isBefore(Instant.parse(original.observedAt())), "创建复核早于原发出观察，请核对时钟与原件");
        return new CreationCheck(
                SCHEMA,
                tenant.toString(),
                factory,
                user.getId().toString(),
                transfer.getId().toString(),
                transfer.getRevision().toString(),
                s.getArrangeId(),
                s.getNodeCode(),
                s.getWorksiteCode(),
                s.getLocationId(),
                s.getBindingRevision(),
                s.getReferenceHash(),
                q.plannedQuantity(),
                q.completedQuantity(),
                q.maximumReportQuantity(),
                q.remainingReportQuantity(),
                q.deliveryQuantity(),
                transfer.getMatUnit(),
                q.overReportAllowed(),
                q.overReportRate(),
                observedAt.toString(),
                false,
                false);
    }

    static void verify(
            CreationCheck c, Snapshot frozen, String tenant, String factory, String actor) {
        need(
                c != null && SCHEMA.equals(c.schemaVersion()) && frozen.routeTarget() != null,
                "创建复核原件不完整");
        for (String value :
                List.of(
                        c.tenantId(),
                        c.actorId(),
                        c.transferId(),
                        c.transferRevision(),
                        c.arrangeId(),
                        c.locationId(),
                        c.bindingRevision())) id(value);
        var s = frozen.routeTarget().selection();
        need(
                tenant.equals(c.tenantId())
                        && factory.equals(c.factoryCode())
                        && actor.equals(c.actorId())
                        && frozen.transferId().equals(c.transferId())
                        && frozen.transferRevision().equals(c.transferRevision())
                        && s.getArrangeId().equals(c.arrangeId())
                        && s.getNodeCode().equals(c.nodeCode())
                        && s.getWorksiteCode().equals(c.worksiteCode())
                        && s.getLocationId().equals(c.locationId())
                        && s.getBindingRevision().equals(c.bindingRevision())
                        && s.getReferenceHash().equals(c.referenceHash())
                        && Objects.equals(frozen.unit(), c.unit())
                        && code(c.unit())
                        && !c.reserved()
                        && !c.productionRelease(),
                "创建复核身份、目标、单位或放行标志不一致");
        var planned = number(c.plannedQuantity(), false);
        var completed = number(c.completedQuantity(), true);
        var maximum = number(c.maximumReportQuantity(), false);
        var remaining = number(c.remainingReportQuantity(), true);
        var quantity = delivery(c.deliveryQuantity());
        var rate = number(c.overReportRate(), true);
        need(
                rate.compareTo(BigDecimal.ONE) <= 0
                        && (c.overReportAllowed() || rate.signum() == 0)
                        && planned.multiply(BigDecimal.ONE.add(rate)).compareTo(maximum) == 0
                        && maximum.subtract(completed).compareTo(remaining) == 0
                        && quantity.compareTo(remaining) <= 0
                        && same(quantity, delivery(frozen.quantity())),
                "创建复核数量或冻结策略不守恒");
        need(
                !Instant.parse(c.observedAt())
                        .isBefore(Instant.parse(frozen.routeTarget().observedAt())),
                "创建复核早于原发出观察");
    }

    static void verifyCreateTime(CreationCheck check, Long eventCreatedAt) {
        need(
                check != null
                        && eventCreatedAt != null
                        && eventCreatedAt > 0
                        && Instant.parse(check.observedAt()).toEpochMilli() <= eventCreatedAt,
                "创建复核晚于CREATE首事件");
    }

    static void verifySnapshotJson(String raw) {
        try {
            var snapshot = new ObjectMapper().readTree(raw);
            var node = snapshot.get("creationCheck");
            if (node == null || node.isNull()) return;
            for (String field :
                    List.of(
                            "transferId",
                            "transferRevision",
                            "sourceStockId",
                            "sourceReportId",
                            "quantity",
                            "unit")) need(snapshot.path(field).isTextual(), "绑定任务来源与配送数量须为完整文本");
            need(node.isObject(), "创建复核原件格式不正确");
            for (String field :
                    List.of(
                            "schemaVersion",
                            "tenantId",
                            "factoryCode",
                            "actorId",
                            "transferId",
                            "transferRevision",
                            "arrangeId",
                            "nodeCode",
                            "worksiteCode",
                            "locationId",
                            "bindingRevision",
                            "referenceHash",
                            "plannedQuantity",
                            "completedQuantity",
                            "maximumReportQuantity",
                            "remainingReportQuantity",
                            "deliveryQuantity",
                            "unit",
                            "overReportRate",
                            "observedAt")) need(node.path(field).isTextual(), "创建复核身份与数量须为完整文本");
            for (String field : List.of("overReportAllowed", "reserved", "productionRelease"))
                need(node.path(field).isBoolean(), "创建复核标志未明确");
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("创建复核原件损坏", e);
        }
    }
}
