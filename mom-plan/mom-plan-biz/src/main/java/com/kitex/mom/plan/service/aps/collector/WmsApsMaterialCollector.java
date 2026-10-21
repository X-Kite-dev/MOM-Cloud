package com.kitex.mom.plan.service.aps.collector;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.util.R;
import com.kitex.mom.wms.agent.dto.WmsInventoryHealthDTO;
import com.kitex.mom.wms.api.feign.RemoteWmsAgentQueryService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 采集库存、预留、质量限制和库存数据完整性证据。 */
@Component
public class WmsApsMaterialCollector extends AbstractApsResourceCollector {

    private final RemoteWmsAgentQueryService service;

    public WmsApsMaterialCollector(ObjectMapper objectMapper, RemoteWmsAgentQueryService service) {
        super(objectMapper);
        this.service = service;
    }

    @Override public String module() { return "WMS"; }
    @Override public int order() { return 30; }

    @Override
    public PlnApsResourceEvidence collect(PlnApsResourceCollectionContext context) {
        try {
            var query = new WmsInventoryHealthDTO.Query().setFactoryCode(context.factoryCode()).setLimit(50);
            R<WmsInventoryHealthDTO.Evidence> response = service.inventoryHealth(query);
            if (response == null || !response.isOk() || response.getData() == null) {
                return unavailable(true, "WMS_INVENTORY_UNAVAILABLE", "MATERIAL", "INVENTORY_HEALTH",
                        "库存证据不可用", null, "/wms/wmsLiveStock/index");
            }
            WmsInventoryHealthDTO.Evidence data = response.getData();
            List<PlnApsResourceEvidence.Gap> gaps = gaps(data.getDataGaps(), "WMS_INVENTORY_GAP", "MATERIAL",
                    "INVENTORY_HEALTH", data.getTruncated(), "/wms/wmsLiveStock/index");
            Map<String, Object> summary = Map.of("inventory", data);
            String version = stableFingerprint(summary);
            return evidence(true, gaps.isEmpty() ? "COMPLETE" : "PARTIAL", version, data.getObservedAt(), summary,
                    data.getEvidenceRefs() == null ? List.of() : data.getEvidenceRefs(), gaps);
        } catch (Exception e) {
            return unavailable(true, "WMS_INVENTORY_CALL_FAILED", "MATERIAL", "INVENTORY_HEALTH",
                    "库存证据调用失败", e, "/wms/wmsLiveStock/index");
        }
    }

    private List<PlnApsResourceEvidence.Gap> gaps(List<String> source, String prefix, String category,
                                                  String type, Boolean truncated, String route) {
        List<PlnApsResourceEvidence.Gap> result = new ArrayList<>();
        if (source != null) for (int i = 0; i < source.size(); i++) {
            result.add(new PlnApsResourceEvidence.Gap(prefix + '_' + (i + 1), category, "BLOCKER", type,
                    null, "库存证据存在缺口", source.get(i), route, Map.of()));
        }
        if (Boolean.TRUE.equals(truncated)) result.add(new PlnApsResourceEvidence.Gap(prefix + "_TRUNCATED",
                category, "BLOCKER", type, null, "库存证据被截断", "库存候选被截断，禁止按不完整数据认定齐套。", route, Map.of()));
        return result;
    }
}
