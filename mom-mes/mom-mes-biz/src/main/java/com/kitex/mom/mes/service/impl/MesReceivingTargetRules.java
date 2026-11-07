package com.kitex.mom.mes.service.impl;

import com.kitex.mom.mdm.dto.MdmReceivingLocationDTO;
import com.kitex.mom.mdm.dto.MdmReceivingLocationDTO.*;
import com.kitex.mom.mdm.feign.RemoteMdmReceivingLocationService;
import com.kitex.mom.mdm.support.MdmReceivingReferenceFingerprint;
import com.kitex.mom.mes.dto.MesWipNextStationsDTO.Station;

import java.util.*;

/** 发出前与发出后复用同一套实际/计划工位及权威收货位置规则。 */
final class MesReceivingTargetRules {
    private MesReceivingTargetRules() {}

    static void need(boolean ok, String message) {
        if (!ok) throw new IllegalArgumentException(message);
    }

    static Long id(String value) {
        need(value != null && value.matches("[1-9][0-9]{0,18}"), "身份/版本须为完整Long文本");
        try {
            return Long.valueOf(value);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("身份/版本超出范围");
        }
    }

    static boolean code(String value) {
        if (value == null || value.isBlank() || value.length() > 100 || !value.equals(value.trim()))
            return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 32) return false;
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(++i)))
                    return false;
            } else if (Character.isLowSurrogate(c)) return false;
        }
        return true;
    }

    private static boolean present(String value) {
        return value != null && !value.isEmpty();
    }

    static Set<String> worksites(Station a) {
        if (present(a.actualWorksiteCode())) {
            need(code(a.actualWorksiteCode()), "确认工位编码不正确");
            return Set.of(a.actualWorksiteCode());
        }
        var allowed = new LinkedHashSet<String>();
        if (present(a.worksiteCodes())) {
            need(a.worksiteCodes().length() <= 20199, "候选工位文本超出范围");
            for (String part : a.worksiteCodes().split(",", -1)) {
                String value = part.trim();
                need(code(value) && allowed.add(value) && allowed.size() <= 200, "派工候选工位格式或数量不正确");
            }
        }
        if (present(a.plannedWorksiteCode())) {
            need(code(a.plannedWorksiteCode()), "计划工位编码不正确");
            need(allowed.isEmpty() || allowed.contains(a.plannedWorksiteCode()), "计划工位与候选名单不一致");
            return Set.of(a.plannedWorksiteCode());
        }
        need(!allowed.isEmpty(), "派工没有可明确选择的工位");
        return allowed;
    }

    static RuntimeView resolve(
            RemoteMdmReceivingLocationService remote,
            String tenant,
            String factory,
            String actor,
            Station target,
            String worksite) {
        need(
                code(worksite)
                        && code(target.workshopCode())
                        && worksites(target).contains(worksite),
                "所选工位不属于该后继派工的实际/计划范围");
        var response = remote.runtime(worksite);
        need(response != null && response.isOk() && response.getData() != null, "MDM收货位置暂不可核验");
        var m = response.getData();
        need(
                MdmReceivingLocationDTO.SCHEMA.equals(m.schemaVersion())
                        && tenant.equals(m.tenantId())
                        && factory.equals(m.factoryCode())
                        && actor.equals(m.actorId())
                        && m.ready()
                        && m.station() != null
                        && m.station().valid()
                        && worksite.equals(m.station().code())
                        && target.workshopCode().equals(m.station().workshopCode())
                        && m.bindings() != null
                        && m.bindings().size() <= 100,
                "MDM位置实名、工位、车间或配置结构不一致");
        var s = m.station();
        for (String value :
                Arrays.asList(s.id(), s.lineId(), s.workshopId(), s.factoryId(), s.industryId()))
            id(value);
        for (String value : Arrays.asList(s.code(), s.lineCode(), s.workshopCode()))
            need(code(value), "工位路径编码不完整");
        var seen = new HashSet<String>();
        for (var b : m.bindings()) {
            need(
                    b != null
                            && b.reference() != null
                            && b.reference().station() != null
                            && b.reference().point() != null,
                    "收货位置来源不完整");
            var r = b.reference();
            var p = r.point();
            need(
                    r.station().equals(s)
                            && p.valid()
                            && Objects.equals(b.stationId(), s.id())
                            && Objects.equals(b.locationId(), p.id())
                            && seen.add(b.locationId()),
                    "收货位置身份重复或不一致");
            for (String value :
                    Arrays.asList(
                            b.stationId(),
                            b.locationId(),
                            b.revision(),
                            p.warehouseId(),
                            p.areaId(),
                            p.shelfId())) id(value);
            need(
                    code(p.code())
                            && code(p.warehouseCode())
                            && Objects.equals(
                                    r.hash(),
                                    MdmReceivingReferenceFingerprint.of(tenant, factory, s, p)),
                    "收货位置结构指纹不一致");
        }
        return m;
    }
}
