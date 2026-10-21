package com.kitex.mom.qms.agent.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.mom.qms.agent.dto.QmsCapaEvidenceDTO;
import com.kitex.mom.qms.agent.service.QmsAgentFactoryAccessService;
import com.kitex.mom.qms.agent.service.QmsCapaEvidenceQueryService;
import com.kitex.mom.qms.entity.*;
import com.kitex.mom.qms.mapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 一致性快照聚合全部CAPA周期和来源NCR证据；不推进阶段、不更新主单、不执行外部业务。
 * 指纹覆盖规范化的全部事实，不复用仅覆盖关闭周期的局部指纹。
 */
@Service
@RequiredArgsConstructor
public class QmsCapaEvidenceQueryServiceImpl implements QmsCapaEvidenceQueryService {
    public static final String TOOL_CODE = "QMS_CAPA_8D_EVIDENCE_QUERY@1";

    private final QmsAgentFactoryAccessService factoryAccess;
    private final PermissionService permissionService;
    private final ObjectMapper objectMapper;
    private final QmsCapaMapper capaMapper;
    private final QmsCapaCauseMapper causeMapper;
    private final QmsCapaActionMapper actionMapper;
    private final QmsCapaOperationMapper operationMapper;
    private final QmsCapaMesRetestReopenReceiptMapper reopenMapper;
    private final QmsBadInfoMapper badMapper;
    private final QmsBadInfoDetailMapper badDetailMapper;
    private final QmsBadOperationMapper badOperationMapper;
    private final QmsMrbProposalMapper mrbProposalMapper;
    private final QmsMrbOperationMapper mrbOperationMapper;

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public QmsCapaEvidenceDTO.Evidence query(Long capaId) {
        require(capaId != null && capaId > 0, "CAPA主键无效");
        String factory = factoryAccess.requireRead(FactoryContextHolder.getFactoryCode());
        Long tenant = TenantContextHolder.getTenantId();
        require(permissionService.hasPermission("capa:show"), "当前岗位无权读取CAPA证据");

        QmsCapaEntity capa = capaMapper.selectOne(new LambdaQueryWrapper<QmsCapaEntity>()
                .eq(QmsCapaEntity::getTenantId, tenant).eq(QmsCapaEntity::getFactoryCode, factory)
                .eq(QmsCapaEntity::getId, capaId));
        require(capa != null, "当前实际租户和工厂未找到CAPA");
        List<QmsCapaCauseEntity> causes = causeMapper.selectList(new LambdaQueryWrapper<QmsCapaCauseEntity>()
                .eq(QmsCapaCauseEntity::getTenantId, tenant).eq(QmsCapaCauseEntity::getFactoryCode, factory)
                .eq(QmsCapaCauseEntity::getCapaId, capaId).orderByAsc(QmsCapaCauseEntity::getId));
        List<QmsCapaActionEntity> actions = actionMapper.selectList(new LambdaQueryWrapper<QmsCapaActionEntity>()
                .eq(QmsCapaActionEntity::getTenantId, tenant).eq(QmsCapaActionEntity::getFactoryCode, factory)
                .eq(QmsCapaActionEntity::getCapaId, capaId).orderByAsc(QmsCapaActionEntity::getId));
        List<QmsCapaOperationEntity> operations = operationMapper.selectList(new LambdaQueryWrapper<QmsCapaOperationEntity>()
                .eq(QmsCapaOperationEntity::getTenantId, tenant).eq(QmsCapaOperationEntity::getFactoryCode, factory)
                .eq(QmsCapaOperationEntity::getCapaId, capaId).orderByAsc(QmsCapaOperationEntity::getId));
        List<QmsCapaMesRetestReopenReceiptEntity> receipts = reopenMapper.selectList(
                new LambdaQueryWrapper<QmsCapaMesRetestReopenReceiptEntity>()
                        .eq(QmsCapaMesRetestReopenReceiptEntity::getTenantId, tenant)
                        .eq(QmsCapaMesRetestReopenReceiptEntity::getFactoryCode, factory)
                        .eq(QmsCapaMesRetestReopenReceiptEntity::getCapaId, capaId)
                        .orderByAsc(QmsCapaMesRetestReopenReceiptEntity::getId));

        List<String> refs = new ArrayList<>();
        refs.add(reference("qms_capa", capaId));
        causes.forEach(item -> refs.add(reference("qms_capa_cause", item.getId())));
        actions.forEach(item -> refs.add(reference("qms_capa_action", item.getId())));
        operations.forEach(item -> refs.add(reference("qms_capa_operation", item.getId())));
        receipts.forEach(item -> refs.add(reference("qms_capa_mes_retest_reopen_receipt", item.getId())));
        List<String> missing = new ArrayList<>();
        if (capa.getCycleNo() == null || capa.getCycleNo() < 1) {
            missing.add("CURRENT_CYCLE_MISSING:案件缺少权威当前周期，不得以历史措施推断当前阶段完成");
        }
        Map<String, Object> sourceNcr = sourceNcr(capa, tenant, factory, refs, missing);

        QmsCapaEvidenceDTO.Evidence result = new QmsCapaEvidenceDTO.Evidence()
                .setToolCode(TOOL_CODE).setContractVersion(1).setTenantId(String.valueOf(tenant))
                .setFactoryCode(factory).setCapaId(String.valueOf(capaId)).setCapaNo(capa.getCapaNo())
                .setCurrentCycle(capa.getCycleNo()).setStatus(capa.getStatus()).setObservedAt(LocalDateTime.now())
                .setCapa(row(capa)).setSourceNcr(sourceNcr).setCauses(rows(causes)).setActions(rows(actions))
                .setOperations(rows(operations)).setReopenReceipts(rows(receipts)).setEvidenceRefs(refs)
                .setStageCompletion(stages(capa, causes, actions, missing)).setMissingEvidence(missing);
        // 观察时刻及派生缺口不进入事实指纹，重复读取相同业务事实必须得到相同指纹。
        Map<String, Object> facts = new TreeMap<>();
        facts.put("contractVersion", 1);
        facts.put("tenantId", result.getTenantId());
        facts.put("factoryCode", factory);
        facts.put("capa", result.getCapa());
        facts.put("sourceNcr", sourceNcr);
        facts.put("causes", result.getCauses());
        facts.put("actions", result.getActions());
        facts.put("operations", result.getOperations());
        facts.put("reopenReceipts", result.getReopenReceipts());
        result.setSourceFingerprint(fingerprint(facts));
        return result;
    }

    private Map<String, Object> sourceNcr(QmsCapaEntity capa, Long tenant, String factory,
                                          List<String> refs, List<String> missing) {
        if (!"NCR".equals(capa.getSourceType())) {
            return Map.of();
        }
        // CAPA查看权限不扩大为全部NCR处置事实的查看权限；缺权限明确留作证据缺口。
        if (!permissionService.hasPermission("bad:show")) {
            missing.add("SOURCE_NCR_PERMISSION:当前岗位无NCR查看权限，未取得来源NCR处置证据");
            return Map.of();
        }
        QmsBadInfoEntity bad = badMapper.selectOne(new LambdaQueryWrapper<QmsBadInfoEntity>()
                .eq(QmsBadInfoEntity::getTenantId, tenant).eq(QmsBadInfoEntity::getFactoryCode, factory)
                .eq(QmsBadInfoEntity::getId, capa.getSourceId()));
        if (bad == null || !Objects.equals(bad.getNcrNo(), capa.getSourceNo())) {
            missing.add("SOURCE_NCR_MISSING:当前组织范围未找到与CAPA冻结编号一致的来源NCR");
            return Map.of();
        }
        List<QmsBadInfoDetailEntity> details = badDetailMapper.selectList(new LambdaQueryWrapper<QmsBadInfoDetailEntity>()
                .eq(QmsBadInfoDetailEntity::getTenantId, tenant).eq(QmsBadInfoDetailEntity::getFactoryCode, factory)
                .eq(QmsBadInfoDetailEntity::getBadId, bad.getId()).orderByAsc(QmsBadInfoDetailEntity::getId));
        List<QmsBadOperationEntity> operations = badOperationMapper.selectList(new LambdaQueryWrapper<QmsBadOperationEntity>()
                .eq(QmsBadOperationEntity::getTenantId, tenant).eq(QmsBadOperationEntity::getFactoryCode, factory)
                .eq(QmsBadOperationEntity::getBadId, bad.getId()).orderByAsc(QmsBadOperationEntity::getId));
        List<QmsMrbProposal> proposals = mrbProposalMapper.selectList(new LambdaQueryWrapper<QmsMrbProposal>()
                .eq(QmsMrbProposal::getTenantId, tenant).eq(QmsMrbProposal::getFactoryCode, factory)
                .eq(QmsMrbProposal::getBadId, bad.getId()).orderByAsc(QmsMrbProposal::getId));
        List<QmsMrbOperation> mrbOperations = proposals.isEmpty() ? List.of() : mrbOperationMapper.selectList(
                new LambdaQueryWrapper<QmsMrbOperation>().eq(QmsMrbOperation::getTenantId, tenant)
                        .eq(QmsMrbOperation::getFactoryCode, factory)
                        .in(QmsMrbOperation::getProposalId, proposals.stream().map(QmsMrbProposal::getId).toList())
                        .orderByAsc(QmsMrbOperation::getId));
        refs.add(reference("qms_bad_info", bad.getId()));
        details.forEach(item -> refs.add(reference("qms_bad_info_detail", item.getId())));
        operations.forEach(item -> refs.add(reference("qms_bad_operation", item.getId())));
        proposals.forEach(item -> refs.add(reference("qms_mrb_proposal", item.getId())));
        mrbOperations.forEach(item -> refs.add(reference("qms_mrb_operation", item.getId())));
        Map<String, Object> source = row(bad);
        source.put("details", rows(details));
        source.put("operations", rows(operations));
        source.put("mrbProposals", rows(proposals));
        source.put("mrbOperations", rows(mrbOperations));
        return source;
    }

    private Map<String, Boolean> stages(QmsCapaEntity capa, List<QmsCapaCauseEntity> allCauses,
                                        List<QmsCapaActionEntity> allActions, List<String> missing) {
        List<QmsCapaActionEntity> active = allActions.stream()
                .filter(item -> capa.getCycleNo() != null && Objects.equals(item.getCycleNo(), capa.getCycleNo()))
                .filter(item -> !"CANCELLED".equals(item.getStatus()) && !"REJECTED".equals(item.getStatus())).toList();
        List<QmsCapaActionEntity> containment = active.stream()
                .filter(item -> "CONTAINMENT".equals(item.getActionType())).toList();
        List<QmsCapaActionEntity> execution = active.stream()
                .filter(item -> "CORRECTIVE".equals(item.getActionType()) || "PREVENTIVE".equals(item.getActionType())).toList();
        List<QmsCapaCauseEntity> confirmed = allCauses.stream()
                .filter(item -> capa.getCycleNo() != null && Objects.equals(item.getCycleNo(), capa.getCycleNo()))
                .filter(item -> "CONFIRMED".equals(item.getVerificationStatus()))
                .filter(item -> StrUtil.isNotBlank(item.getEvidence()) && StrUtil.isNotBlank(item.getVerifierCode())
                        && !Objects.equals(item.getProposedBy(), item.getVerifierCode())).toList();
        Map<String, Boolean> stages = new LinkedHashMap<>();
        stage(stages, missing, "D1", StrUtil.isNotBlank(capa.getTeamMembers()) && StrUtil.isNotBlank(capa.getCoordinatorCode()), "团队或协调人证据未完成");
        stage(stages, missing, "D2", StrUtil.isNotBlank(capa.getProblemStatement()) && StrUtil.isNotBlank(capa.getImpactScope()), "问题陈述或影响范围未完成");
        stage(stages, missing, "D3", !containment.isEmpty() && containment.stream().allMatch(this::completed), "当前周期围堵措施及客观完成证据未完成");
        stage(stages, missing, "D4", confirmed.stream().anyMatch(item -> "OCCURRENCE".equals(item.getCauseType()))
                && confirmed.stream().anyMatch(item -> "ESCAPE".equals(item.getCauseType())), "当前周期发生/逃逸根因尚未分别独立确认");
        stage(stages, missing, "D5", execution.stream().anyMatch(item -> "CORRECTIVE".equals(item.getActionType()))
                && execution.stream().anyMatch(item -> "PREVENTIVE".equals(item.getActionType()))
                && execution.stream().allMatch(item -> StrUtil.isNotBlank(item.getOwnerCode()) && item.getDueTime() != null), "当前周期纠正/预防措施、责任人或期限未完成");
        stage(stages, missing, "D6", !execution.isEmpty() && execution.stream().allMatch(this::completed), "当前周期纠正/预防措施及客观实施证据未完成");
        stage(stages, missing, "D7", StrUtil.isNotBlank(capa.getEffectivenessCriteria())
                && capa.getEffectivenessReviewTime() != null && StrUtil.isNotBlank(capa.getStandardizationScope()), "有效性标准、观察期或防再发标准化范围未完成");
        stage(stages, missing, "D8", "CLOSED".equals(capa.getStatus()) && "PASS".equals(capa.getEffectivenessResult())
                && StrUtil.isNotBlank(capa.getEffectivenessEvidence()) && StrUtil.isNotBlank(capa.getLessonsLearned())
                && capa.getClosedTime() != null && capa.getVerificationTime() != null && StrUtil.isNotBlank(capa.getVerifierCode())
                && !Objects.equals(capa.getCoordinatorCode(), capa.getVerifierCode())
                && execution.stream().noneMatch(item -> Objects.equals(item.getOwnerCode(), capa.getVerifierCode())), "当前周期有效性独立验证、经验教训或权威关闭证据未完成");
        if ("CANCELLED".equals(capa.getStatus())) missing.add("CAPA_CANCELLED:案件已取消，不得将报告作为已闭环8D");
        return stages;
    }

    private boolean completed(QmsCapaActionEntity action) {
        return "COMPLETED".equals(action.getStatus()) && StrUtil.isNotBlank(action.getEvidence())
                && StrUtil.isNotBlank(action.getCompletionResult()) && action.getCompletedTime() != null;
    }

    private void stage(Map<String, Boolean> stages, List<String> missing, String code, boolean complete, String reason) {
        stages.put(code, complete);
        if (!complete) missing.add(code + ":" + reason);
    }

    private List<Map<String, Object>> rows(List<?> entities) {
        return entities.stream().map(this::row).toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> row(Object entity) {
        Map<String, Object> value = (Map<String, Object>) canonical(objectMapper.valueToTree(entity));
        value.remove("overdueFlag");
        return value;
    }

    /**
     * 稳定键顺序并保持Long主键和精确小数为字符串，避免跨前端序列化损失精度。
     */
    private Object canonical(JsonNode node) {
        if (node.isObject()) {
            Map<String, Object> value = new TreeMap<>();
            node.fields().forEachRemaining(field -> value.put(field.getKey(), canonical(field.getValue())));
            return value;
        }
        if (node.isArray()) {
            List<Object> value = new ArrayList<>();
            node.forEach(item -> value.add(canonical(item)));
            return value;
        }
        if (node.isNull()) return null;
        if (node.isTextual()) return node.textValue();
        if (node.isBoolean()) return node.booleanValue();
        if (node.isInt()) return node.intValue();
        if (node.isIntegralNumber()) return node.asText();
        if (node.isNumber()) return node.decimalValue().stripTrailingZeros().toPlainString();
        return node.asText();
    }

    private String fingerprint(Map<String, Object> facts) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(objectMapper.writeValueAsString(facts).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new CheckedException("CAPA全周期证据指纹生成失败", failure);
        }
    }

    private String reference(String table, Long id) {
        return "QMS:" + table + ":" + id;
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }
}
