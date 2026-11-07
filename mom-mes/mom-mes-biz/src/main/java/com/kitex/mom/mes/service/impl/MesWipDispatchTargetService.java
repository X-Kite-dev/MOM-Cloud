package com.kitex.mom.mes.service.impl;

import static com.kitex.mom.mes.dto.MesWipDispatchTargetDTO.SCHEMA;
import static com.kitex.mom.mes.service.impl.MesReceivingTargetRules.*;

import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.entity.MdmWarehouseLocationEntity;
import com.kitex.mom.mdm.feign.RemoteMdmReceivingLocationService;
import com.kitex.mom.mes.dto.*;
import com.kitex.mom.mes.dto.MesWipDispatchTargetDTO.*;
import com.kitex.mom.mes.entity.MesWarehouseLiveStockEntity;
import com.kitex.mom.mes.mapper.MesWipNextStationsMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
public class MesWipDispatchTargetService {
    private final MesActualFactoryAccessService access;
    private final MesWipNextStationsService next;
    private final MesWipNextStationsMapper db;
    private final RemoteMdmReceivingLocationService mdm;

    private record Scope(String tenant, String factory, String actor) {}

    private Scope scope(String permission) {
        String factory = access.require(permission);
        var user = SecurityUtils.getUser();
        need(
                user != null
                        && user.getId() != null
                        && user.getId() > 0
                        && Objects.equals(user.getTenantId(), TenantContextHolder.getTenantId()),
                "缺少实际实名租户");
        return new Scope(
                TenantContextHolder.getTenantId().toString(), factory, user.getId().toString());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Options options(String stockId, String arrangeId, String worksite) {
        return options(scope("read"), stockId, arrangeId, worksite);
    }

    private Options options(Scope scope, String stockId, String arrangeId, String worksite) {
        long sid = id(stockId), aid = id(arrangeId);
        var source = db.stock(Long.valueOf(scope.tenant), scope.factory, sid);
        need(
                source != null
                        && Objects.equals(source.getId(), sid)
                        && source.getSourceReportId() != null
                        && source.getNum() != null
                        && source.getNum().signum() > 0
                        && Objects.equals(source.getTenantId(), Long.valueOf(scope.tenant))
                        && Objects.equals(source.getFactoryCode(), scope.factory),
                "实际来源库存身份或正量不可核验");
        var v = next.query(sid, aid > 1 ? aid - 1 : null);
        need(
                v != null
                        && "MES_WIP_NEXT_STATIONS@1".equals(v.schemaVersion())
                        && scope.tenant.equals(v.tenantId())
                        && scope.factory.equals(v.factoryCode())
                        && scope.actor.equals(v.actorId())
                        && stockId.equals(v.stockId())
                        && "READY".equals(v.availability()),
                "原来源冻结路线当前不可用于工序目标选择");
        var rows = v.stations().stream().filter(a -> arrangeId.equals(a.arrangeId())).toList();
        need(rows.size() == 1, "所选派工不是当前有效冻结直接后继");
        var target = rows.get(0);
        var m = resolve(mdm, scope.tenant, scope.factory, scope.actor, target, worksite);
        return new Options(
                SCHEMA,
                scope.tenant,
                scope.factory,
                scope.actor,
                stockId,
                source.getSourceReportId().toString(),
                arrangeId,
                target.nodeCode(),
                worksite,
                m.bindings(),
                Instant.now().toString());
    }

    /** 加入旧发出事务，在扣减前复核；位置取证不承诺分布式锁/质量/承接余量。 */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public Validated validate(
            MesWipTransferCommand command,
            MesWarehouseLiveStockEntity locked,
            MdmWarehouseLocationEntity target) {
        var scope = scope("write");
        MesWipTransferCommandCodec.target(command);
        var c = command.getDispatchTarget();
        need(
                c != null
                        && scope.tenant.equals(c.getTenantId())
                        && scope.factory.equals(c.getFactoryCode())
                        && scope.actor.equals(c.getActorId()),
                "工序目标实际本人或工厂已变化");
        need(
                locked != null
                        && Objects.equals(locked.getId(), command.getSourceStockId())
                        && Objects.equals(locked.getTenantId(), Long.valueOf(scope.tenant))
                        && Objects.equals(locked.getFactoryCode(), scope.factory)
                        && Objects.equals(locked.getSourceReportId(), id(c.getSourceReportId()))
                        && locked.getNum() != null
                        && command.getExpectedSourceQuantity() != null
                        && locked.getNum().compareTo(command.getExpectedSourceQuantity()) == 0,
                "工序目标与已锁定原库存/报工/观察量不一致");
        var v = options(scope, c.getSourceStockId(), c.getArrangeId(), c.getWorksiteCode());
        need(
                v.sourceReportId().equals(c.getSourceReportId())
                        && v.nodeCode().equals(c.getNodeCode()),
                "原报工或冻结后继节点已变化");
        var rows =
                v.options().stream().filter(b -> b.locationId().equals(c.getLocationId())).toList();
        need(rows.size() == 1, "所选收货位置当前不可用");
        var b = rows.get(0);
        var p = b.reference().point();
        need(
                b.revision().equals(c.getBindingRevision())
                        && b.reference().hash().equals(c.getReferenceHash()),
                "工位收货位置版本或结构已变化");
        need(
                target != null
                        && Objects.equals(target.getId(), id(p.id()))
                        && scope.tenant.equals(Objects.toString(target.getTenantId(), null))
                        && scope.factory.equals(target.getFactoryCode())
                        && p.code().equals(target.getLocationCode())
                        && p.code().equals(command.getToLocationCode())
                        && p.warehouseCode().equals(target.getWarehouseCode()),
                "实际目标库位/仓库与工序位置引用不一致");
        return new Validated(c, b.reference(), v.observedAt());
    }
}
