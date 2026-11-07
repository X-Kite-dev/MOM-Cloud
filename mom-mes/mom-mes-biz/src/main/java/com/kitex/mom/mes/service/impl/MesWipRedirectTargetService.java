package com.kitex.mom.mes.service.impl;

import static com.kitex.mom.mes.service.impl.MesReceivingTargetRules.*;

import cn.hutool.json.JSONUtil;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.dto.*;
import com.kitex.mom.mes.dto.MesWipRedirectTargetDTO.*;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.mapper.*;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
public class MesWipRedirectTargetService {
    private final MesActualFactoryAccessService access;
    private final MesDeliveryTargetService targets;
    private final MesWipDestinationChangeMapper db;
    private final MesWipLossCaseMapper loss;
    private final MesWipTargetCapacityService capacity;
    private final MesWipRedirectBasisService basis;
    private final MesWipRedirectEffects redirects;

    private record Source(MesWipTransferEntity transfer, Options options) {}

    private Source read(String transferId, String arrangeId, String worksiteCode) {
        Long tid = id(transferId);
        id(arrangeId);
        need(code(worksiteCode), "须明确选择目标工位");
        String factory = access.require("read");
        Long tenant = TenantContextHolder.getTenantId();
        var user = SecurityUtils.getUser();
        need(
                user != null
                        && user.getId() != null
                        && user.getId() > 0
                        && tenant != null
                        && tenant.equals(user.getTenantId()),
                "当前本人租户身份不可核验");
        var t = db.transfer(tenant, factory, tid);
        need(
                t != null
                        && tid.equals(t.getId())
                        && tenant.equals(t.getTenantId())
                        && factory.equals(t.getFactoryCode())
                        && "0".equals(t.getDelFlag())
                        && "TRANSIT".equals(t.getStatus()),
                "仅当前实际工厂的正向在途原单可复核改向目标");
        redirects.requireOriginalTarget(t);
        MesWipTransferOriginVerifier.verify(t, db.dispatch(tenant, factory, tid));
        BigDecimal sum = BigDecimal.ZERO;
        for (var q :
                Arrays.asList(
                        t.getRemainingQuantity(),
                        t.getReceivedQuantity(),
                        t.getReturnedQuantity(),
                        t.getQuarantinedQuantity(),
                        t.getLostQuantity())) sum = sum.add(physical(q));
        need(
                sum.compareTo(physical(t.getQuantity())) == 0
                        && t.getRemainingQuantity().signum() > 0,
                "原单数量不守恒或已无在途数量");
        need(
                loss.selectCount(
                                new LambdaQueryWrapper<MesWipLossCaseEntity>()
                                        .eq(MesWipLossCaseEntity::getTenantId, tenant)
                                        .eq(MesWipLossCaseEntity::getFactoryCode, factory)
                                        .eq(MesWipLossCaseEntity::getTransferId, tid)
                                        .eq(MesWipLossCaseEntity::getDelFlag, "0")
                                        .in(MesWipLossCaseEntity::getStatus, "PENDING", "APPROVED"))
                        == 0,
                "有待审或待执行丢失核销，请先处理原单");
        var v = targets.options(transferId, arrangeId, worksiteCode);
        need(
                v != null
                        && MesDeliveryTargetDTO.SCHEMA.equals(v.schemaVersion())
                        && tenant.toString().equals(v.tenantId())
                        && factory.equals(v.factoryCode())
                        && user.getId().toString().equals(v.actorId())
                        && transferId.equals(v.transferId())
                        && t.getRevision().toString().equals(v.transferRevision())
                        && arrangeId.equals(v.arrangeId())
                        && worksiteCode.equals(v.worksiteCode()),
                "新目标来源或原单版本已变化");
        var warehouse =
                JSONUtil.parseArray(t.getLocationSnapshot())
                        .getJSONObject(0)
                        .getStr("warehouseCode");
        var positions = JSONUtil.parseArray(t.getLocationSnapshot());
        String originalId = Objects.toString(positions.getJSONObject(1).get("id"), null),
                returnId = Objects.toString(positions.getJSONObject(0).get("id"), null);
        id(originalId);
        id(returnId);
        var options = new ArrayList<Option>();
        for (var o : v.options()) {
            var p = o.binding().reference().point();
            options.add(
                    new Option(
                            o.binding(),
                            !o.matchesOriginalTarget()
                                    && !originalId.equals(p.id())
                                    && !returnId.equals(p.id())
                                    && !t.getToLocationCode().equals(p.code())
                                    && warehouse.equals(p.warehouseCode())
                                    && !t.getFromLocationCode().equals(p.code())));
        }
        return new Source(
                t,
                new Options(
                        MesWipRedirectTargetDTO.SCHEMA,
                        v.tenantId(),
                        v.factoryCode(),
                        v.actorId(),
                        v.transferId(),
                        v.transferRevision(),
                        v.arrangeId(),
                        v.nodeCode(),
                        v.worksiteCode(),
                        t.getToLocationCode(),
                        t.getFromLocationCode(),
                        warehouse,
                        originalId,
                        returnId,
                        plain(t.getRemainingQuantity()),
                        t.getMatUnit(),
                        options,
                        Instant.now().toString()));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Options options(String transferId, String arrangeId, String worksiteCode) {
        return read(transferId, arrangeId, worksiteCode).options();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Check check(Choice c) {
        need(c != null && MesWipRedirectTargetDTO.SCHEMA.equals(c.schemaVersion()), "改向复核协议不正确");
        id(c.tenantId());
        id(c.actorId());
        id(c.transferRevision());
        id(c.locationId());
        id(c.bindingRevision());
        var source = read(c.transferId(), c.arrangeId(), c.worksiteCode());
        var v = source.options();
        need(
                v.tenantId().equals(c.tenantId())
                        && v.factoryCode().equals(c.factoryCode())
                        && v.actorId().equals(c.actorId())
                        && v.transferRevision().equals(c.transferRevision())
                        && v.quantity().equals(c.quantity())
                        && v.unit().equals(c.unit()),
                "身份、在途数量、单位或原单版本已变化，请重新选择");
        var rows =
                v.options().stream()
                        .filter(o -> c.locationId().equals(o.binding().locationId()))
                        .toList();
        need(rows.size() == 1 && rows.get(0).eligible(), "所选位置不是可复核的新目标；退回原处或跨仓请使用对应业务");
        var b = rows.get(0).binding();
        need(
                c.bindingRevision().equals(b.revision())
                        && Objects.equals(c.referenceHash(), b.reference().hash()),
                "新目标收货关系版本或原文指纹已变化");
        var policy = basis.verify(source.transfer(), v.arrangeId());
        var q =
                capacity.observe(
                        source.transfer(),
                        v.arrangeId(),
                        v.nodeCode(),
                        v.worksiteCode(),
                        b.reference().station().workshopCode());
        need(
                policy.overReportAllowed() == q.overReportAllowed()
                        && policy.overReportRate().compareTo(new BigDecimal(q.overReportRate()))
                                == 0,
                "目标数量观察与持久冻结超报策略不一致");
        return new Check(
                MesWipRedirectTargetDTO.SCHEMA,
                v.tenantId(),
                v.factoryCode(),
                v.actorId(),
                c,
                v.nodeCode(),
                b.reference(),
                v.originalLocationCode(),
                q,
                "TARGET_CHANGE_CHECKED",
                Instant.now().toString(),
                false,
                false,
                false);
    }

    private static BigDecimal physical(BigDecimal q) {
        need(
                q != null && q.signum() >= 0 && q.scale() <= 8 && q.precision() - q.scale() <= 16,
                "实物数量未知或超出24/8精度");
        return q;
    }

    private static String plain(BigDecimal q) {
        return physical(q).stripTrailingZeros().toPlainString();
    }
}
