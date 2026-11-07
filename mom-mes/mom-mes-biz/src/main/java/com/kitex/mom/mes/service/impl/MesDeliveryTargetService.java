package com.kitex.mom.mes.service.impl;

import static com.kitex.mom.mes.dto.MesDeliveryTargetDTO.SCHEMA;

import cn.hutool.json.JSONUtil;

import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.feign.RemoteMdmReceivingLocationService;
import com.kitex.mom.mdm.support.MdmReceivingReferenceFingerprint;
import com.kitex.mom.mes.dto.MesDeliveryTargetDTO.*;
import com.kitex.mom.mes.mapper.MesWipNextStationsMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/** 仅核当前位置关系，不预留、不判质量/产能、不创建配送或实收。 */
@Service
@RequiredArgsConstructor
public class MesDeliveryTargetService {
    private final MesActualFactoryAccessService access;
    private final MesWipNextStationsService next;
    private final MesWipNextStationsMapper db;
    private final RemoteMdmReceivingLocationService mdm;
    private final MesWipRedirectEffects redirects;

    private record Scope(String tenant, String factory, String actor) {}

    private Scope scope() {
        String f = access.require("read");
        var user = SecurityUtils.getUser();
        need(
                user != null
                        && user.getId() != null
                        && Objects.equals(user.getTenantId(), TenantContextHolder.getTenantId()),
                "缺少实际实名租户");
        return new Scope(TenantContextHolder.getTenantId().toString(), f, user.getId().toString());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Options options(String transfer, String arrange, String worksite) {
        var s = scope();
        return options(s, transfer, arrange, worksite);
    }

    private Options options(Scope s, String transfer, String arrange, String worksite) {
        Long tid = id(transfer), aid = id(arrange);
        need(text(worksite), "须明确选择工位");
        var v = next.queryTransfer(tid, aid > 1 ? aid - 1 : null);
        need(
                v != null
                        && s.tenant.equals(v.tenantId())
                        && s.factory.equals(v.factoryCode())
                        && s.actor.equals(v.actorId())
                        && transfer.equals(v.transferId())
                        && "TRANSIT".equals(v.status())
                        && new BigDecimal(v.remainingQuantity()).signum() > 0,
                "仅当前实际工厂正量在途转运可核对配送目标");
        need("READY".equals(v.routing().availability()), "冻结后继尚不可用于目标核对");
        var candidates =
                v.routing().stations().stream().filter(x -> arrange.equals(x.arrangeId())).toList();
        need(candidates.size() == 1, "所选派工不是当前有效冻结直接后继");
        var target = candidates.get(0);
        var m =
                MesReceivingTargetRules.resolve(
                        mdm, s.tenant, s.factory, s.actor, target, worksite);
        var doc = db.transfer(Long.valueOf(s.tenant), s.factory, tid);
        need(doc != null, "原转运位置快照缺失");
        redirects.requireOriginalTarget(doc);
        var locations = JSONUtil.parseArray(doc.getLocationSnapshot());
        need(locations.size() == 2, "原转运目标位置快照不完整");
        var original = locations.getJSONObject(1);
        String originalId = Objects.toString(original.get("id"), "");
        id(originalId);
        need(v.toLocationCode().equals(original.getStr("locationCode")), "原转运目标库位不一致");
        String warehouse = original.getStr("warehouseCode");
        need(text(warehouse), "原目标仓库未留存");
        var ids = new HashSet<String>();
        List<Option> result = new ArrayList<>();
        for (var binding : m.bindings()) {
            var ref = binding.reference();
            need(
                    ref != null
                            && ref.station() != null
                            && ref.point() != null
                            && ref.station().equals(m.station())
                            && ref.point().valid()
                            && binding.stationId().equals(m.station().id())
                            && binding.locationId().equals(ref.point().id())
                            && ids.add(binding.locationId()),
                    "收货位置身份重复或不一致");
            id(binding.stationId());
            id(binding.locationId());
            id(binding.revision());
            need(
                    Objects.equals(
                            ref.hash(),
                            MdmReceivingReferenceFingerprint.of(
                                    s.tenant, s.factory, ref.station(), ref.point())),
                    "收货位置结构指纹不一致");
            result.add(
                    new Option(
                            binding,
                            originalId.equals(ref.point().id())
                                    && v.toLocationCode().equals(ref.point().code())
                                    && warehouse.equals(ref.point().warehouseCode())
                                    && boundMatches(
                                            v.boundTarget(),
                                            arrange,
                                            target.nodeCode(),
                                            worksite,
                                            binding)));
        }
        return new Options(
                SCHEMA,
                s.tenant,
                s.factory,
                s.actor,
                transfer,
                v.transferRevision(),
                arrange,
                target.nodeCode(),
                worksite,
                result,
                Instant.now().toString());
    }

    private boolean boundMatches(
            com.kitex.mom.mes.dto.MesWipDispatchTargetDTO.Selection original,
            String arrange,
            String node,
            String worksite,
            com.kitex.mom.mdm.dto.MdmReceivingLocationDTO.RuntimeBinding binding) {
        return original == null
                || (Objects.equals(original.getArrangeId(), arrange)
                        && Objects.equals(original.getNodeCode(), node)
                        && Objects.equals(original.getWorksiteCode(), worksite)
                        && Objects.equals(original.getLocationId(), binding.locationId())
                        && Objects.equals(original.getBindingRevision(), binding.revision())
                        && Objects.equals(original.getReferenceHash(), binding.reference().hash()));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Match check(Choice c) {
        var s = scope();
        need(
                c != null
                        && SCHEMA.equals(c.schemaVersion())
                        && s.tenant.equals(c.tenantId())
                        && s.factory.equals(c.factoryCode())
                        && s.actor.equals(c.actorId()),
                "目标核对实名或工厂已变化");
        id(c.transferRevision());
        id(c.locationId());
        id(c.bindingRevision());
        var v = options(s, c.transferId(), c.arrangeId(), c.worksiteCode());
        need(c.transferRevision().equals(v.transferRevision()), "转运单版本已变化，请重新核对");
        var rows =
                v.options().stream()
                        .filter(o -> c.locationId().equals(o.binding().locationId()))
                        .toList();
        need(rows.size() == 1, "所选收货位置当前不可用");
        var selected = rows.get(0);
        need(
                c.bindingRevision().equals(selected.binding().revision())
                        && Objects.equals(c.referenceHash(), selected.binding().reference().hash()),
                "收货位置配置版本已变化，请重新选择");
        need(selected.matchesOriginalTarget(), "所选位置与已发出转运目标不一致，须先按受控改站流程办理");
        return new Match(
                SCHEMA,
                s.tenant,
                s.factory,
                s.actor,
                c,
                "TARGET_MATCHED",
                false,
                Instant.now().toString());
    }

    private static Long id(String s) {
        need(s != null && s.matches("[1-9][0-9]{0,18}"), "ID/版本须为完整Long文本");
        try {
            return Long.valueOf(s);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("ID/版本超出范围");
        }
    }

    private static boolean text(String s) {
        return s != null && !s.isBlank() && s.length() <= 100;
    }

    private static void need(boolean b, String m) {
        if (!b) throw new IllegalArgumentException(m);
    }
}
