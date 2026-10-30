package com.kitex.mom.plan.service.aps.collector;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 采集案件自身已核验的 PLAN 来源谱系。
 */
@Component
public class PlanApsResourceCollector extends AbstractApsResourceCollector {

    public PlanApsResourceCollector(ObjectMapper objectMapper) {
        super(objectMapper);
    }

    @Override
    public String module() {
        return "PLAN";
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public PlnApsResourceEvidence collect(PlnApsResourceCollectionContext context) {
        List<PlnApsResourceEvidence.Gap> gaps = new ArrayList<>();
        if (context.sources() == null || context.sources().isEmpty()) {
            gaps.add(new PlnApsResourceEvidence.Gap("PLAN_SOURCE_MISSING", "PLAN", "BLOCKER",
                    "APS_CASE", null, "排产来源缺失", "案件没有服务端核验的正式业务来源，禁止计算。",
                    "/plan/plnSchedulingWorkbench/index", Map.of("caseId", context.caseId().toString())));
        }
        if (context.planningPolicyId() == null || context.planningPolicyVersion() == null
                || context.planningPolicyFingerprint() == null || context.mrpAuthority() == null) {
            gaps.add(new PlnApsResourceEvidence.Gap("PLAN_AUTHORITY_POLICY_MISSING", "PLAN", "BLOCKER",
                    "FACTORY_PLANNING_POLICY", context.factoryCode(), "工厂计划权责策略未生效",
                    "必须由工厂级权责策略明确 ERP_MRP 或 MOM_MRP，禁止用本地默认值代替。",
                    "/mdm/factory/index", Map.of("factoryCode", context.factoryCode())));
        }
        if (context.formalPlanContractId() == null || context.formalPlanContractFingerprint() == null) {
            gaps.add(new PlnApsResourceEvidence.Gap("FORMAL_PLAN_CONTRACT_MISSING", "PLAN", "BLOCKER",
                    "FORMAL_PLAN_CONTRACT", null, "正式计划合同未建立",
                    "当前来源尚未绑定可核验的正式计划合同，资源可浏览但禁止进入求解和发布。",
                    "/plan/plnSchedulingWorkbench/index", Map.of("caseId", context.caseId().toString())));
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("caseId", context.caseId().toString());
        summary.put("caseFingerprint", context.caseFingerprint());
        summary.put("sources", context.sources());
        summary.put("productCodes", context.productCodes());
        summary.put("planNos", context.planNos());
        summary.put("planningPolicyId", context.planningPolicyId());
        summary.put("planningPolicyVersion", context.planningPolicyVersion());
        summary.put("planningPolicyFingerprint", context.planningPolicyFingerprint());
        summary.put("mrpAuthority", context.mrpAuthority());
        summary.put("formalPlanContractId", context.formalPlanContractId());
        summary.put("formalPlanContractFingerprint", context.formalPlanContractFingerprint());
        String version = stableFingerprint(summary);
        return evidence(true, gaps.isEmpty() ? "COMPLETE" : "PARTIAL", version, LocalDateTime.now(),
                summary, context.sources().stream().map(source -> source.type() + ":" + source.code()).toList(), gaps);
    }
}
