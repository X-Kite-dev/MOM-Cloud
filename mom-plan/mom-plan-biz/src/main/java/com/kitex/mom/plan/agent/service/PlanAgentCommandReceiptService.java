package com.kitex.mom.plan.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.plan.agent.entity.PlanAgentCommandProofEntity;
import com.kitex.mom.plan.dto.PlanResourceLocalReplanDTO;
import com.kitex.mom.plan.dto.PlanSchedulingAgentDTO;
import com.kitex.mom.plan.dto.PlanShortageLocalReplanDTO;
import com.kitex.mom.plan.dto.PlnSchedulingScenarioRunDTO;
import com.kitex.mom.plan.entity.PlnResourceReplanCaseEntity;
import com.kitex.mom.plan.entity.PlnSchedulingScenarioEntity;
import com.kitex.mom.plan.entity.PlnShortageReplanCaseEntity;
import com.kitex.mom.plan.mapper.PlnResourceReplanCaseMapper;
import com.kitex.mom.plan.mapper.PlnSchedulingScenarioMapper;
import com.kitex.mom.plan.mapper.PlnShortageReplanCaseMapper;
import com.kitex.mom.plan.service.PlnResourceLocalReplanService;
import com.kitex.mom.plan.service.PlnSchedulingAgentService;
import com.kitex.mom.plan.service.PlnSchedulingEngineService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * 永久意图与原业务账的只读核查。没有完整命令意图的旧历史一律UNKNOWN。
 * 自动恢复仅消费能证明完整原载荷的情景/案件账，不调用任何决策、发布或执行服务。
 * MRP三类及通用APS选案只有已确认完整proof可回执，缺完整证明保持UNKNOWN供人工核账。
 */
@Service
public class PlanAgentCommandReceiptService {
    private final PlanAgentCommandProofService proofs;
    private final PlanAgentCommandCodec codec;
    private final ObjectMapper mapper;
    private final PlnSchedulingScenarioMapper scenarios;
    private final PlnShortageReplanCaseMapper shortages;
    private final PlnResourceReplanCaseMapper resources;
    private final ObjectProvider<PlnSchedulingAgentService> scheduling;
    private final ObjectProvider<PlnResourceLocalReplanService> resourceService;
    private final PlanAgentCommandOccupancyGuard occupancy;
    private final ObjectProvider<PlnSchedulingEngineService> engine;

    public PlanAgentCommandReceiptService(PlanAgentCommandProofService proofs, PlanAgentCommandCodec codec,
                                          ObjectMapper mapper, PlnSchedulingScenarioMapper scenarios,
                                          PlnShortageReplanCaseMapper shortages, PlnResourceReplanCaseMapper resources,
                                          ObjectProvider<PlnSchedulingAgentService> scheduling,
                                          ObjectProvider<PlnResourceLocalReplanService> resourceService,
                                          PlanAgentCommandOccupancyGuard occupancy, ObjectProvider<PlnSchedulingEngineService> engine) {
        this.proofs = proofs;
        this.codec = codec;
        this.mapper = mapper;
        this.scenarios = scenarios;
        this.shortages = shortages;
        this.resources = resources;
        this.scheduling = scheduling;
        this.resourceService = resourceService;
        this.occupancy = occupancy;
        this.engine = engine;
    }

    /**
     * 精确作用域和完整载荷先核验，CONFIRMED仅代表原命令执行，不代表收益或交付已完成。
     */
    public Map<String, Object> lookup(PlanAgentCommandCodec.LookupQuery query) throws Exception {
        proofs.requireScope(query.factoryCode());
        PlanAgentCommandCodec.Frozen frozen;
        try {
            frozen = codec.fromLookup(query);
        } catch (RuntimeException invalid) {
            return envelope(query, "CONFLICT", null, null, "原命令与永久请求号契约不一致");
        }
        PlanAgentCommandProofEntity proof = proofs.find(query.requestId(), false);
        if (proof == null) {
            try {
                if (occupancy.absent(frozen, query.factoryCode())) {
                    return envelope(query, "ABSENT", null, null, "真实领域明确未发现该永久号意图或旧业务请求，仅首次执行可继续原审批闸门");
                }
            } catch (RuntimeException unavailable) {
                return envelope(query, "UNKNOWN", null, null, "原业务永久号占用无法权威核实，禁止下发");
            }
            return envelope(query, "UNKNOWN", null, null, "旧业务永久号已占用但无完整意图证明，不能冒认或重新执行");
        }
        try {
            proofs.requireSame(proof, frozen);
        } catch (CheckedException conflict) {
            return envelope(query, "CONFLICT", proof.getId(), null, "永久请求号与原工具、分支或完整载荷不一致");
        }
        if ("CONFIRMED".equals(proof.getStatus())) {
            return envelope(query, "CONFIRMED", proof.getId(), mapper.readTree(proof.getReceiptSnapshot()), "原领域永久回执已确认");
        }
        Object receipt;
        try {
            receipt = recover(query, proof, mapper.readTree(proof.getCommandSnapshot()));
        } catch (RuntimeException unavailable) {
            return envelope(query, "UNKNOWN", proof.getId(), null, "原业务持久证据暂时不可权威核实；禁止重新下发");
        }
        if (receipt == null) {
            return envelope(query, "UNKNOWN", proof.getId(), null, "原命令意图存在，但实际业务永久账尚未完整确认；禁止重新下发");
        }
        proofs.confirm(proof.getId(), receipt);
        return envelope(query, "CONFIRMED", proof.getId(), receipt, "已通过原业务永久账只读恢复回执；未重发命令");
    }

    private Object recover(PlanAgentCommandCodec.LookupQuery query, PlanAgentCommandProofEntity proof, JsonNode command) throws Exception {
        return switch (proof.getToolCode()) {
            // 旧MRP/WMS账不能完整证明原审批的所有前置参数，仅原proof已确认时可恢复。
            // 没有该完整证明，宁可UNKNOWN人工核账，绝不把只匹配请求号的旧回执冒认成功。
            case "PLAN_MRP_SUBSTITUTE_DECISION", "PLAN_MRP_PURCHASE_ORDER_SUBMIT", "PLAN_MRP_TRANSFER_DECISION" -> null;
            case "PLAN_SHORTAGE_LOCAL_REPLAN_PUBLISH" -> recoverShortage(query, proof, command);
            case "PLAN_APS_SCENARIO_RUN", "PLAN_APS_SCENARIO_SELECT", "PLAN_APS_SCENARIO_PUBLISH" ->
                    "RESOURCE_REPLAN".equals(proof.getCommandVariant())
                            ? recoverResource(query, proof, command) : recoverAps(query, proof, command);
            default -> null;
        };
    }

    private Object recoverShortage(PlanAgentCommandCodec.LookupQuery query, PlanAgentCommandProofEntity proof, JsonNode command) {
        PlnShortageReplanCaseEntity item = shortages.selectOne(new LambdaQueryWrapper<PlnShortageReplanCaseEntity>()
                .eq(PlnShortageReplanCaseEntity::getFactoryCode, query.factoryCode())
                .eq(PlnShortageReplanCaseEntity::getId, command.path("caseId").asLong()).last("LIMIT 1"));
        if (item == null || !query.requestId().equals(item.getPublishRequestId()) || !"PUBLISHED".equals(item.getStatus())
                || item.getPublishedTime() == null || item.getMesPublishedFingerprint() == null
                || item.getPublishedTime().isBefore(proof.getCreateTime()) || !proof.getActorCode().equals(item.getPublisherCode())
                || !Objects.equals(item.getMrpId(), command.path("mrpId").asLong())
                || !Objects.equals(item.getIssueNo(), command.path("issueNo").asText())
                || !Objects.equals(item.getProposalFingerprint(), command.path("expectedProposalFingerprint").asText())
                || !Objects.equals(item.getMesExecutionFingerprint(), command.path("expectedMesExecutionFingerprint").asText())) {
            return null;
        }
        return new PlanShortageLocalReplanDTO.PublishReceipt().setCaseId(item.getId()).setCaseNo(item.getCaseNo())
                .setIssueNo(item.getIssueNo()).setStatus(item.getStatus()).setMovedOperationCount(item.getMovedOperationCount())
                .setNewCompletionTime(item.getProposedCompletionTime()).setMesAfterExecutionFingerprint(item.getMesPublishedFingerprint());
    }

    private Object recoverAps(PlanAgentCommandCodec.LookupQuery query, PlanAgentCommandProofEntity proof, JsonNode command) throws Exception {
        LambdaQueryWrapper<PlnSchedulingScenarioEntity> criteria = new LambdaQueryWrapper<PlnSchedulingScenarioEntity>()
                .eq(PlnSchedulingScenarioEntity::getFactoryCode, query.factoryCode());
        boolean run = "PLAN_APS_SCENARIO_RUN".equals(query.toolCode());
        if (run) {
            criteria.eq(PlnSchedulingScenarioEntity::getRequestId, query.requestId());
        } else {
            criteria.eq(PlnSchedulingScenarioEntity::getScenarioNo, command.path("scenarioNo").asText());
        }
        PlnSchedulingScenarioEntity scenario = scenarios.selectOne(criteria.last("LIMIT 1"));
        if (scenario == null || scenario.getCandidateCount() == null || scenario.getCandidateCount() < 1
                || Set.of("FAILED", "EVALUATING", "CANCELLED").contains(scenario.getScenarioStatus())) {
            return null;
        }
        PlanSchedulingAgentDTO.ScenarioQuery lookup = new PlanSchedulingAgentDTO.ScenarioQuery();
        lookup.setFactoryCode(query.factoryCode());
        lookup.setScenarioId(scenario.getId());
        if (run) {
            PlnSchedulingScenarioRunDTO original = mapper.treeToValue(command, PlnSchedulingScenarioRunDTO.class);
            if (scenario.getCreateTime() == null || scenario.getCreateTime().isBefore(proof.getCreateTime())
                    || !Objects.equals(scenario.getRequestFingerprint(), engine.getObject().agentPermanentRequestFingerprint(original))) {
                return null;
            }
            if (original.getExpectedObjectiveTemplateFingerprint() != null) {
                if (scenario.getInputSnapshot() == null) {
                    return null;
                }
                JsonNode input = mapper.readTree(scenario.getInputSnapshot());
                JsonNode frozenTemplate = mapper.readTree(input.path("objectiveTemplateSnapshot").asText("{}"));
                if (!original.getExpectedObjectiveTemplateFingerprint().equals(frozenTemplate.path("contentFingerprint").asText())) {
                    return null;
                }
            }
            return scheduling.getObject().query(lookup);
        }
        if ("PLAN_APS_SCENARIO_SELECT".equals(query.toolCode())) {
            // 原通用选案账未保存expectedScenarioFingerprint/expectedCandidateFingerprint。
            // 仅候选ID、原因和sourceFingerprint不构成完整Agent命令证明，不能自动确认。
            return null;
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("factory", command.path("factoryCode").asText().trim());
        fields.put("scenario", command.path("scenarioNo").asText().trim());
        fields.put("task", command.path("selectedTaskId").asLong());
        fields.put("scenarioFingerprint", command.path("expectedScenarioFingerprint").asText());
        fields.put("selectionFingerprint", command.path("expectedSelectionFingerprint").asText());
        fields.put("approvalId", command.path("agentApprovalId").asLong());
        fields.put("approvalNo", command.path("agentApprovalNo").asText());
        fields.put("applicant", command.path("applicantCode").asText());
        fields.put("approver", command.path("approverCode").asText());
        if (!query.requestId().equals(scenario.getPublishRequestId()) || !Set.of("PUBLISHED", "PARTIAL").contains(scenario.getScenarioStatus())
                || !Objects.equals(scenario.getPublishRequestFingerprint(), hash(mapper.writeValueAsString(fields)))
                || !Objects.equals(scenario.getSelectedTaskId(), command.path("selectedTaskId").asLong())
                || !Objects.equals(scenario.getAgentApprovalId(), command.path("agentApprovalId").asLong())
                || !Objects.equals(scenario.getAgentApprovalNo(), command.path("agentApprovalNo").asText())
                || scenario.getPublishedTime() == null || scenario.getPublishedTime().isBefore(proof.getCreateTime())
                || !proof.getActorCode().equals(scenario.getPublishedBy())) {
            return null;
        }
        return new PlanSchedulingAgentDTO.PublishReceipt().setScenarioId(scenario.getId()).setScenarioNo(scenario.getScenarioNo())
                .setTaskId(scenario.getSelectedTaskId()).setScenarioStatus(scenario.getScenarioStatus())
                .setPublishRequestId(scenario.getPublishRequestId()).setPublishRequestFingerprint(scenario.getPublishRequestFingerprint())
                .setAgentApprovalNo(scenario.getAgentApprovalNo()).setPublishedBy(scenario.getPublishedBy()).setPublishedTime(scenario.getPublishedTime());
    }

    private Object recoverResource(PlanAgentCommandCodec.LookupQuery query, PlanAgentCommandProofEntity proof, JsonNode command) throws Exception {
        boolean select = "PLAN_APS_SCENARIO_SELECT".equals(query.toolCode());
        PlnResourceReplanCaseEntity item = resources.selectOne(new LambdaQueryWrapper<PlnResourceReplanCaseEntity>()
                .eq(PlnResourceReplanCaseEntity::getFactoryCode, query.factoryCode())
                .eq(PlnResourceReplanCaseEntity::getId, command.path(select ? "candidateCaseId" : "caseId").asLong()).last("LIMIT 1"));
        if (item == null) {
            return null;
        }
        if (select) {
            PlnSchedulingScenarioEntity scenario = scenarios.selectOne(new LambdaQueryWrapper<PlnSchedulingScenarioEntity>()
                    .eq(PlnSchedulingScenarioEntity::getFactoryCode, query.factoryCode())
                    .eq(PlnSchedulingScenarioEntity::getId, item.getScenarioId()).last("LIMIT 1"));
            return scenario != null && query.requestId().equals(scenario.getSelectionRequestId())
                    && Objects.equals(scenario.getSelectedTaskId(), item.getProposalTaskId())
                    && Objects.equals(scenario.getSelectionReason(), command.path("reason").asText().trim())
                    && Objects.equals(scenario.getSourceFingerprint(), command.path("expectedSourceFingerprint").asText())
                    && Objects.equals(scenario.getSelectionFingerprint(), hash(query.requestId().trim() + '|' + item.getId()
                    + '|' + command.path("expectedSourceFingerprint").asText() + '|' + command.path("reason").asText().trim()))
                    && proof.getActorCode().equals(scenario.getSelectedBy()) && scenario.getSelectedTime() != null
                    && !scenario.getSelectedTime().isBefore(proof.getCreateTime())
                    ? resourceService.getObject().scenario(item.getScenarioId()) : null;
        }
        if (!query.requestId().equals(item.getPublishRequestId()) || !"PUBLISHED".equals(item.getStatus())
                || item.getMesPublishedFingerprint() == null || item.getPublishedTime() == null
                || item.getPublishedTime().isBefore(proof.getCreateTime()) || !proof.getActorCode().equals(item.getPublisherCode())
                || !Objects.equals(item.getProposalFingerprint(), command.path("expectedProposalFingerprint").asText())
                || !Objects.equals(item.getResourceEvidenceFingerprint(), command.path("expectedResourceEvidenceFingerprint").asText())
                || !Objects.equals(item.getMesExecutionFingerprint(), command.path("expectedMesExecutionFingerprint").asText())) {
            return null;
        }
        return new PlanResourceLocalReplanDTO.PublishReceipt().setCaseId(item.getId()).setCaseNo(item.getCaseNo())
                .setIssueNo(item.getIssueNo()).setStatus(item.getStatus()).setMovedOperationCount(item.getMovedOperationCount())
                .setReassignedOperationCount(item.getReassignedOperationCount()).setNewCompletionTime(item.getProposedCompletionTime())
                .setMesAfterExecutionFingerprint(item.getMesPublishedFingerprint());
    }

    private Map<String, Object> envelope(PlanAgentCommandCodec.LookupQuery query, String status, Long proofId, Object receipt, String reason) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status);
        result.put("factoryCode", query.factoryCode());
        result.put("toolCode", query.toolCode());
        result.put("requestId", query.requestId());
        result.put("proofId", proofId == null ? null : String.valueOf(proofId));
        result.put("receipt", receipt);
        result.put("reason", reason);
        return result;
    }

    private String hash(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
