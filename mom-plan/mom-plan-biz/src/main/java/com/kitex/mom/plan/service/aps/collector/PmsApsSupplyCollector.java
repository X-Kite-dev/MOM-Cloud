package com.kitex.mom.plan.service.aps.collector;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.util.R;
import com.kitex.mom.pms.agent.dto.PmsProcurementRiskDTO;
import com.kitex.mom.pms.api.feign.RemotePmsAgentQueryService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 采集采购在途、供应承诺和交付风险证据。
 */
@Component
public class PmsApsSupplyCollector extends AbstractApsResourceCollector {

    private final RemotePmsAgentQueryService service;

    public PmsApsSupplyCollector(ObjectMapper objectMapper, RemotePmsAgentQueryService service) {
        super(objectMapper);
        this.service = service;
    }

    @Override
    public String module() {
        return "PMS";
    }

    @Override
    public int order() {
        return 70;
    }

    @Override
    public PlnApsResourceEvidence collect(PlnApsResourceCollectionContext context) {
        try {
            int horizonDays = Math.max(1, Math.min(90,
                    (int) java.time.Duration.between(context.horizonStart(), context.horizonEnd()).toDays() + 1));
            var query = new PmsProcurementRiskDTO.Query().setFactoryCode(context.factoryCode())
                    .setHorizonDays(horizonDays).setLimit(50);
            R<PmsProcurementRiskDTO.Evidence> response = service.procurementRisk(query);
            if (response == null || !response.isOk() || response.getData() == null) {
                return unavailable(false, "PMS_SUPPLY_UNAVAILABLE", "SUPPLY", "PROCUREMENT_RISK",
                        "采购供应证据不可用", null, "/pms/order/index");
            }
            PmsProcurementRiskDTO.Evidence data = response.getData();
            List<PlnApsResourceEvidence.Gap> gaps = new ArrayList<>();
            if (data.getDataGaps() != null) for (int i = 0; i < data.getDataGaps().size(); i++) {
                gaps.add(new PlnApsResourceEvidence.Gap("PMS_SUPPLY_GAP_" + (i + 1), "SUPPLY", "WARNING",
                        "PROCUREMENT_RISK", null, "供应承诺证据存在缺口", data.getDataGaps().get(i),
                        "/pms/order/index", Map.of()));
            }
            if (Boolean.TRUE.equals(data.getTruncated())) gaps.add(new PlnApsResourceEvidence.Gap(
                    "PMS_SUPPLY_TRUNCATED", "SUPPLY", "WARNING", "PROCUREMENT_RISK", null,
                    "采购风险证据被截断", "仅展示风险前50项，正式计算前应进一步收窄物料范围。",
                    "/pms/order/index", Map.of()));
            Map<String, Object> summary = Map.of("supply", data);
            String version = stableFingerprint(summary);
            return evidence(false, gaps.isEmpty() ? "COMPLETE" : "PARTIAL", version, data.getObservedAt(), summary,
                    data.getEvidenceRefs() == null ? List.of() : data.getEvidenceRefs(), gaps);
        } catch (Exception e) {
            return unavailable(false, "PMS_SUPPLY_CALL_FAILED", "SUPPLY", "PROCUREMENT_RISK",
                    "采购供应证据调用失败", e, "/pms/order/index");
        }
    }
}
