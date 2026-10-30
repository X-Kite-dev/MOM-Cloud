package com.kitex.mom.plan.service.aps.collector;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.util.R;
import com.kitex.mom.mes.api.feign.RemoteMesPlanExecutionService;
import com.kitex.mom.mes.dto.MesPlanExecutionEvidenceDTO;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 采集已下达计划的在制、锁定和可移动性证据。
 */
@Component
public class MesApsExecutionCollector extends AbstractApsResourceCollector {

    private final RemoteMesPlanExecutionService service;

    public MesApsExecutionCollector(ObjectMapper objectMapper, RemoteMesPlanExecutionService service) {
        super(objectMapper);
        this.service = service;
    }

    @Override
    public String module() {
        return "MES";
    }

    @Override
    public int order() {
        return 40;
    }

    @Override
    public PlnApsResourceEvidence collect(PlnApsResourceCollectionContext context) {
        if (context.planNos().isEmpty()) {
            var gap = new PlnApsResourceEvidence.Gap("MES_PLAN_SCOPE_EMPTY", "EXECUTION", "WARNING",
                    "PLAN_EXECUTION", null, "暂无可映射的生产计划号",
                    "当前来源尚未形成生产计划，MES在制证据将在计划生成后补采。",
                    "/mes/mesTask/index", Map.of("caseId", context.caseId().toString()));
            return evidence(false, "PARTIAL", "NO_PLAN_SCOPE", LocalDateTime.now(),
                    Map.of("planNos", List.of()), List.of(), List.of(gap));
        }
        try {
            var query = new MesPlanExecutionEvidenceDTO.Query().setFactoryCode(context.factoryCode())
                    .setPlanNos(context.planNos()).setIncludeProductionEvents(false);
            R<MesPlanExecutionEvidenceDTO.Evidence> response = service.query(query);
            if (response == null || !response.isOk() || response.getData() == null) {
                return unavailable(true, "MES_EXECUTION_UNAVAILABLE", "EXECUTION", "PLAN_EXECUTION",
                        "MES在制证据不可用", null, "/mes/mesTask/index");
            }
            MesPlanExecutionEvidenceDTO.Evidence data = response.getData();
            List<PlnApsResourceEvidence.Gap> gaps = new ArrayList<>();
            if (data.getPlans() != null) for (MesPlanExecutionEvidenceDTO.Plan plan : data.getPlans()) {
                if (plan.getGaps() == null) continue;
                for (int i = 0; i < plan.getGaps().size(); i++)
                    gaps.add(new PlnApsResourceEvidence.Gap(
                            "MES_EXECUTION_GAP_" + sha256(plan.getPlanNo() + ':' + i).substring(0, 12), "EXECUTION", "BLOCKER",
                            "PLAN_EXECUTION", plan.getPlanNo(), "MES执行证据存在缺口", plan.getGaps().get(i),
                            "/mes/mesTask/index", Map.of("planNo", plan.getPlanNo())));
            }
            Map<String, Object> summary = Map.of("execution", data);
            String version = stableFingerprint(summary);
            LocalDateTime observed = data.getObservedAt() == null ? LocalDateTime.now() : LocalDateTime.parse(data.getObservedAt());
            return evidence(true, gaps.isEmpty() ? "COMPLETE" : "PARTIAL", version, observed, summary,
                    context.planNos().stream().map(no -> "MES:PLAN:" + no).toList(), gaps);
        } catch (Exception e) {
            return unavailable(true, "MES_EXECUTION_CALL_FAILED", "EXECUTION", "PLAN_EXECUTION",
                    "MES在制证据调用失败", e, "/mes/mesTask/index");
        }
    }
}
