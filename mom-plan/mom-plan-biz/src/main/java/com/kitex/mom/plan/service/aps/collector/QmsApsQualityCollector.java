package com.kitex.mom.plan.service.aps.collector;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.util.R;
import com.kitex.mom.qms.agent.dto.QmsInspectionOverviewDTO;
import com.kitex.mom.qms.api.feign.RemoteQmsAgentQueryService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 采集排程时域内待检、不合格、紧急放行等质量约束证据。 */
@Component
public class QmsApsQualityCollector extends AbstractApsResourceCollector {

    private final RemoteQmsAgentQueryService service;

    public QmsApsQualityCollector(ObjectMapper objectMapper, RemoteQmsAgentQueryService service) {
        super(objectMapper);
        this.service = service;
    }

    @Override public String module() { return "QMS"; }
    @Override public int order() { return 60; }

    @Override
    public PlnApsResourceEvidence collect(PlnApsResourceCollectionContext context) {
        try {
            var query = new QmsInspectionOverviewDTO.Query().setFactoryCode(context.factoryCode())
                    .setStartTime(context.horizonStart()).setEndTime(context.horizonEnd()).setLimit(50);
            R<QmsInspectionOverviewDTO.Evidence> response = service.inspectionOverview(query);
            if (response == null || !response.isOk() || response.getData() == null) {
                return unavailable(true, "QMS_QUALITY_UNAVAILABLE", "QUALITY", "QUALITY_OVERVIEW",
                        "质量约束证据不可用", null, "/qms/inspectionProcedureTask/index");
            }
            QmsInspectionOverviewDTO.Evidence data = response.getData();
            List<PlnApsResourceEvidence.Gap> gaps = new ArrayList<>();
            if (data.getDataGaps() != null) for (int i = 0; i < data.getDataGaps().size(); i++) {
                gaps.add(new PlnApsResourceEvidence.Gap("QMS_QUALITY_GAP_" + (i + 1), "QUALITY", "BLOCKER",
                        "QUALITY_OVERVIEW", null, "质量约束证据存在缺口", data.getDataGaps().get(i),
                        "/qms/inspectionProcedureTask/index", Map.of()));
            }
            if (Boolean.TRUE.equals(data.getTruncated())) gaps.add(new PlnApsResourceEvidence.Gap(
                    "QMS_QUALITY_TRUNCATED", "QUALITY", "BLOCKER", "QUALITY_OVERVIEW", null,
                    "质量异常证据被截断", "质量异常超过采集上限，不能按不完整结果放行排程。",
                    "/qms/inspectionProcedureTask/index", Map.of()));
            Map<String, Object> summary = Map.of("quality", data);
            String version = stableFingerprint(summary);
            return evidence(true, gaps.isEmpty() ? "COMPLETE" : "PARTIAL", version, data.getObservedAt(), summary,
                    data.getEvidenceRefs() == null ? List.of() : data.getEvidenceRefs(), gaps);
        } catch (Exception e) {
            return unavailable(true, "QMS_QUALITY_CALL_FAILED", "QUALITY", "QUALITY_OVERVIEW",
                    "质量约束证据调用失败", e, "/qms/inspectionProcedureTask/index");
        }
    }
}
