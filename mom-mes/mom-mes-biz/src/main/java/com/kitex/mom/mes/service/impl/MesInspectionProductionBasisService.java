package com.kitex.mom.mes.service.impl;

import com.fasterxml.jackson.databind.*;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.dto.MesInspectionProductionBasisDTO;
import com.kitex.mom.mes.dto.MesInspectionProductionBasisDTO.*;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.mapper.*;
import com.kitex.mom.qms.dto.inspection.InspectionProcedureSourceContract;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Service
@RequiredArgsConstructor
public class MesInspectionProductionBasisService {
    private static final ObjectMapper JSON =
            new ObjectMapper().enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
    private final MesQmsInspectionCommandMapper commands;
    private final MesInspectionProductionBasisMapper rows;
    private final MesEngineeringPackageService engineering;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Evidence query(Query q) {
        MesQualityGateSourceEvidence.scope();
        Long tenant = TenantContextHolder.getTenantId();
        String factory = FactoryContextHolder.getFactoryCode();
        require(
                q != null
                        && text(q.requestId(), 200)
                        && q.requestId()
                                .startsWith(InspectionProcedureSourceContract.REQUEST_PREFIX)
                        && text(q.operationCode(), 100)
                        && q.commandSnapshot() != null
                        && q.commandSnapshot().length() <= 65536
                        && q.commandFingerprint() != null
                        && q.commandFingerprint().matches("[0-9a-f]{64}"),
                "检验生产依据缺少完整原命令身份");
        var c = commands.original(tenant, q.requestId());
        require(
                c != null
                        && c.getId() != null
                        && c.getId() > 0
                        && "0".equals(c.getDelFlag())
                        && Objects.equals(tenant, c.getTenantId())
                        && factory.equals(c.getFactoryCode())
                        && q.requestId().equals(c.getRequestId())
                        && q.operationCode().equals(c.getSourceTaskCode())
                        && InspectionProcedureSourceContract.VERSION.equals(c.getCommandVersion())
                        && Set.of("OUTBOUND_STARTED", "QMS_CONFIRMED")
                                .contains(Objects.toString(c.getStatus(), ""))
                        && q.commandSnapshot().equals(c.getCommandSnapshot())
                        && q.commandFingerprint().equals(c.getCommandFingerprint())
                        && hash(q.commandSnapshot()).equals(q.commandFingerprint()),
                "检验来源未实际出站，或永久命令身份/原文不一致");
        var source = MesQualityGateSourceEvidence.parse(q.commandSnapshot());
        require(
                q.requestId().equals(source.get("sourceRequestId"))
                        && q.operationCode().equals(source.get("sourceTaskCode"))
                        && q.operationCode().equals(source.get("taskCode"))
                        && text(source.get("orderNo"), 100)
                        && source.get("num") != null
                        && source.get("num").matches("[1-9][0-9]{0,9}")
                        && Long.parseLong(source.get("num")) <= Integer.MAX_VALUE,
                "检验原命令来源或数量缺少明确依据");
        var op = one(rows.operation(tenant, factory, q.operationCode()));
        var task = one(rows.order(tenant, factory, source.get("orderNo")));
        require(
                Objects.equals(tenant, op.getTenantId())
                        && factory.equals(op.getFactoryCode())
                        && Objects.equals(tenant, task.getTenantId())
                        && factory.equals(task.getFactoryCode())
                        && q.operationCode().equals(op.getCode())
                        && source.get("orderNo").equals(task.getCode())
                        && Objects.equals(task.getCode(), op.getTaskCode())
                        && (source.get("orderId") == null
                                || Objects.equals(
                                        source.get("orderId"), String.valueOf(task.getId())))
                        && Objects.equals(source.get("matCode"), task.getMatCode())
                        && Objects.equals(source.get("matCode"), op.getMatCode())
                        && Objects.equals(source.get("processCode"), op.getRouteCode())
                        && Objects.equals(op.getProcessRouteId(), task.getProcessRouteId())
                        && Objects.equals(op.getProcessRouteCode(), task.getProcessRouteCode()),
                "检验来源与实际工单、工序及产品归属不一致");
        if (text(task.getReworkRequestId(), 200)
                || task.getReworkMaterialPlanId() != null
                || "REWORK".equals(task.getResourceType()))
            return evidence(
                    q,
                    source,
                    task,
                    "REWORK_UNAVAILABLE",
                    null,
                    null,
                    List.of("REWORK_APPROVED_BASIS_REQUIRED"));
        if (task.getEngineeringReleaseId() == null
                && task.getEngineeringVersionNo() == null
                && task.getEngineeringBundleSnapshot() == null
                && task.getEngineeringContentHash() == null)
            return evidence(
                    q,
                    source,
                    task,
                    "LEGACY_UNAVAILABLE",
                    null,
                    null,
                    List.of("ORIGINAL_ENGINEERING_MISSING"));
        var original = engineering.readIssuedOriginal(task);
        JsonNode body;
        try {
            body = JSON.readTree(original.source().getBundleSnapshot());
        } catch (Exception e) {
            throw new CheckedException("原工程包不可解析", e);
        }
        String node = op.getSourceRouteNodeCode();
        require(node != null && node.matches("RN-[1-9][0-9]{0,18}"), "实际工序缺少稳定工程节点，不能按主工序猜测");
        var nodes = body.path("graph").path("procedureList");
        int found = 0;
        for (var n : nodes)
            if (node.equals(n.path("routeNodeCode").asText())) {
                found++;
                require(op.getRouteCode().equals(n.path("processCode").asText()), "实际稳定节点与原主工序冲突");
            }
        require(found == 1, "实际稳定节点未唯一出现在原工程路线图");
        require(
                task.getMatCode().equals(body.path("bom").path("productCode").asText()),
                "原BOM产品与受检产品不一致，不能采用其数量单位");
        var raw = body.path("bom").get("unit");
        String unit =
                raw != null && raw.isTextual() && text(raw.textValue(), 40)
                        ? raw.textValue()
                        : null;
        require(raw == null || raw.isNull() || raw.isTextual(), "原BOM数量单位格式损坏");
        require(
                unit != null || raw == null || raw.isNull() || raw.textValue().isBlank(),
                "原BOM数量单位超过允许长度");
        return evidence(
                q,
                source,
                task,
                unit == null ? "PARTIAL" : "FROZEN",
                node,
                unit,
                unit == null ? List.of("QUANTITY_UNIT_MISSING") : List.of());
    }

    private Evidence evidence(
            Query q,
            Map<String, String> s,
            MesTaskEntity t,
            String state,
            String node,
            String unit,
            List<String> gaps) {
        boolean frozen = node != null;
        return new Evidence(
                MesInspectionProductionBasisDTO.SCHEMA,
                state,
                t.getTenantId().toString(),
                t.getFactoryCode(),
                q.requestId(),
                q.commandFingerprint(),
                q.operationCode(),
                t.getCode(),
                String.valueOf(t.getId()),
                s.get("matCode"),
                s.get("processCode"),
                s.get("num"),
                frozen ? t.getEngineeringReleaseId().toString() : null,
                frozen ? t.getEngineeringVersionNo().toString() : null,
                frozen ? t.getEngineeringContentHash() : null,
                node,
                frozen ? t.getBomId().toString() : null,
                frozen ? t.getBomVersion() : null,
                unit,
                gaps);
    }

    private static <T> T one(List<T> rows) {
        require(
                rows != null && rows.size() == 1 && rows.get(0) != null,
                "实际组织缺少唯一且已提交的生产来源，请沿原号重试核对");
        return rows.get(0);
    }

    private static boolean text(String s, int max) {
        return s != null && !s.isBlank() && s.length() <= max;
    }

    private static void require(boolean value, String message) {
        if (!value) throw new CheckedException(message);
    }

    private static String hash(String s) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
