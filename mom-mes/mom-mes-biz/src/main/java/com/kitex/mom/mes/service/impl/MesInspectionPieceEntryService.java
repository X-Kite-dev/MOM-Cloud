package com.kitex.mom.mes.service.impl;

import static com.kitex.mom.mes.service.impl.MesPhysicalTransformCodec.require;

import com.fasterxml.jackson.databind.*;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.dto.*;
import com.kitex.mom.mes.dto.MesInspectionPieceEntryDTO.*;
import com.kitex.mom.mes.mapper.*;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** QMS核对实际受检样本的入站事实；原工单/工程及实际组织全部由MES原记录证明。 */
@Service
@RequiredArgsConstructor
public class MesInspectionPieceEntryService {
    private static final ObjectMapper JSON =
            new ObjectMapper()
                    .findAndRegisterModules()
                    .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
    private final MesQmsInspectionCommandMapper commands;
    private final MesInspectionProductionBasisService production;
    private final MesInspectionPieceEntryMapper entries;
    private final MesWorkpieceTimeMapper time;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public View query(Query q) {
        MesQualityGateSourceEvidence.scope();
        require(
                q != null
                        && q.requestId() != null
                        && q.requestId().length() <= 200
                        && q.commandFingerprint() != null
                        && q.commandFingerprint().matches("[0-9a-f]{64}")
                        && q.operationCode() != null
                        && !q.operationCode().isBlank()
                        && q.operationCode().length() <= 100
                        && q.identifier() != null
                        && q.identifier().matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,99}"),
                "入站核对缺少原来源或合法SN");
        Long tenant = TenantContextHolder.getTenantId();
        String factory = FactoryContextHolder.getFactoryCode();
        var command = commands.original(tenant, q.requestId());
        require(
                command != null && q.commandFingerprint().equals(command.getCommandFingerprint()),
                "原报检命令不存在或指纹不符");
        var context =
                production.query(
                        new MesInspectionProductionBasisDTO.Query(
                                q.requestId(),
                                q.operationCode(),
                                command.getCommandSnapshot(),
                                q.commandFingerprint()));
        if (!Set.of("FROZEN", "PARTIAL").contains(context.availability()))
            return view(q, context, "PRODUCTION_BASIS_UNAVAILABLE", null);
        if (!MesWorkpieceEntrySources.available(time))
            return view(q, context, "SCHEMA_UNAVAILABLE", null);
        var candidates =
                entries.candidates(
                        tenant,
                        factory,
                        context.orderCode(),
                        context.operationCode(),
                        q.identifier());
        if (candidates.isEmpty()) return view(q, context, "NOT_RECORDED", null);
        if (candidates.size() > 1) return view(q, context, "AMBIGUOUS", null);
        var session = candidates.get(0);
        require(
                Objects.equals(tenant, session.getTenantId())
                        && factory.equals(session.getFactoryCode())
                        && context.orderCode().equals(session.getTaskCode())
                        && context.operationCode().equals(session.getProduceTaskCode())
                        && q.identifier().equals(session.getIdentifier()),
                "入站候选与实际受检工单工序不符");
        var original = entries.original(tenant, factory, session.getArrangeId(), session.getId());
        require(original != null, "原入站事实缺失或不唯一，禁止补造匹配");
        var checked = MesWorkpieceEntrySources.verify(JSON, original, session);
        var raw = checked.raw();
        require(
                eq(raw, "taskId", context.orderId())
                        && eq(raw, "sourceRouteNodeCode", context.routeNodeCode())
                        && eq(raw, "engineeringReleaseId", context.engineeringReleaseId())
                        && eq(raw, "engineeringContentHash", context.engineeringHash())
                        && raw.path("engineeringBundleSnapshot").isTextual()
                        && sha(raw.path("engineeringBundleSnapshot").textValue())
                                .equals(context.engineeringHash()),
                "入站原工程版本或稳定节点与受检冻结依据不符");
        require(
                raw.path("taskBirthJson").isTextual()
                        && eq(
                                raw,
                                "taskBirthFingerprint",
                                sha(raw.path("taskBirthJson").textValue())),
                "入站工单出生原文损坏");
        try {
            var birth = JSON.readTree(raw.path("taskBirthJson").textValue());
            require(
                    context.orderId().equals(birth.path("id").asText())
                            && context.tenantId().equals(birth.path("tenant_id").asText())
                            && eq(birth, "factory_code", factory),
                    "入站工单出生组织或身份不符");
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new com.kitex.common.core.exception.CheckedException("工单出生原文不可解析");
        }
        return view(q, context, "MATCHED", checked.summary());
    }

    private static boolean eq(JsonNode raw, String key, String value) {
        return raw.path(key).isTextual() && Objects.equals(raw.path(key).textValue(), value);
    }

    private View view(
            Query q,
            MesInspectionProductionBasisDTO.Evidence context,
            String state,
            MesWorkpieceTimeDTO.EntrySource entry) {
        return new View(
                MesInspectionPieceEntryDTO.SCHEMA,
                state,
                context.tenantId(),
                context.factoryCode(),
                q.identifier(),
                context,
                entry);
    }

    private static String sha(String text) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
