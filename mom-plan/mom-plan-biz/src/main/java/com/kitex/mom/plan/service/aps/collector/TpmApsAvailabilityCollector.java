package com.kitex.mom.plan.service.aps.collector;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.util.R;
import com.kitex.mom.tpm.agent.dto.TpmEquipmentOverviewDTO;
import com.kitex.mom.tpm.api.feign.RemoteTpmAgentQueryService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 采集排程时域内设备停机、维修、保养与点检异常证据。
 */
@Component
public class TpmApsAvailabilityCollector extends AbstractApsResourceCollector {

    private final RemoteTpmAgentQueryService service;

    public TpmApsAvailabilityCollector(ObjectMapper objectMapper, RemoteTpmAgentQueryService service) {
        super(objectMapper);
        this.service = service;
    }

    @Override
    public String module() {
        return "TPM";
    }

    @Override
    public int order() {
        return 50;
    }

    @Override
    public PlnApsResourceEvidence collect(PlnApsResourceCollectionContext context) {
        try {
            var query = new TpmEquipmentOverviewDTO.Query().setFactoryCode(context.factoryCode())
                    .setStartTime(context.horizonStart()).setEndTime(context.horizonEnd()).setLimit(50);
            R<TpmEquipmentOverviewDTO.Evidence> response = service.equipmentOverview(query);
            if (response == null || !response.isOk() || response.getData() == null) {
                return unavailable(true, "TPM_AVAILABILITY_UNAVAILABLE", "RESOURCE", "EQUIPMENT_AVAILABILITY",
                        "设备可用性证据不可用", null, "/tpm/repairTask/index");
            }
            TpmEquipmentOverviewDTO.Evidence data = response.getData();
            List<PlnApsResourceEvidence.Gap> gaps = new ArrayList<>();
            if (data.getDataGaps() != null) for (int i = 0; i < data.getDataGaps().size(); i++) {
                gaps.add(new PlnApsResourceEvidence.Gap("TPM_AVAILABILITY_GAP_" + (i + 1), "RESOURCE", "BLOCKER",
                        "EQUIPMENT_AVAILABILITY", null, "设备可用性证据存在缺口", data.getDataGaps().get(i),
                        "/tpm/repairTask/index", Map.of()));
            }
            if (Boolean.TRUE.equals(data.getTruncated())) gaps.add(new PlnApsResourceEvidence.Gap(
                    "TPM_AVAILABILITY_TRUNCATED", "RESOURCE", "BLOCKER", "EQUIPMENT_AVAILABILITY", null,
                    "设备异常证据被截断", "设备异常超过采集上限，须收窄范围或处理异常后再计算。",
                    "/tpm/repairTask/index", Map.of()));
            Map<String, Object> summary = Map.of("equipment", data);
            String version = stableFingerprint(summary);
            return evidence(true, gaps.isEmpty() ? "COMPLETE" : "PARTIAL", version, data.getObservedAt(), summary,
                    data.getEvidenceRefs() == null ? List.of() : data.getEvidenceRefs(), gaps);
        } catch (Exception e) {
            return unavailable(true, "TPM_AVAILABILITY_CALL_FAILED", "RESOURCE", "EQUIPMENT_AVAILABILITY",
                    "设备可用性证据调用失败", e, "/tpm/repairTask/index");
        }
    }
}
