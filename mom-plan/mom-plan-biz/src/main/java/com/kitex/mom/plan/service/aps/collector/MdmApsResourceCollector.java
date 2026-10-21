package com.kitex.mom.plan.service.aps.collector;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.util.R;
import com.kitex.mom.mdm.feign.RemoteFactoryModelService;
import com.kitex.mom.mdm.feign.RemoteCalendarService;
import com.kitex.mom.mdm.feign.RemoteMdmRouteEngineeringService;
import com.kitex.mom.mdm.dto.MdmRouteEngineeringDTO;
import com.kitex.mom.mdm.entity.MdmCalendarEntity;
import com.kitex.mom.mdm.vo.MdmFactoryModelTreeVO;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 采集当前工厂的组织资源树；工程包精确取证在具体产品进入计算前继续复核。 */
@Component
public class MdmApsResourceCollector extends AbstractApsResourceCollector {

    private final RemoteFactoryModelService factoryModelService;
    private final RemoteCalendarService calendarService;
    private final RemoteMdmRouteEngineeringService routeEngineeringService;

    public MdmApsResourceCollector(ObjectMapper objectMapper, RemoteFactoryModelService factoryModelService,
                                   RemoteCalendarService calendarService,
                                   RemoteMdmRouteEngineeringService routeEngineeringService) {
        super(objectMapper);
        this.factoryModelService = factoryModelService;
        this.calendarService = calendarService;
        this.routeEngineeringService = routeEngineeringService;
    }

    @Override public String module() { return "MDM"; }
    @Override public int order() { return 20; }

    @Override
    public PlnApsResourceEvidence collect(PlnApsResourceCollectionContext context) {
        try {
            R<List<MdmFactoryModelTreeVO>> response = factoryModelService.getFactoryModelTree(context.factoryCode());
            if (response == null || !response.isOk() || response.getData() == null) {
                return unavailable(true, "MDM_FACTORY_MODEL_UNAVAILABLE", "RESOURCE", "FACTORY_MODEL",
                        "工厂资源模型不可用", null, "/mdm/factory-model/index");
            }
            List<MdmFactoryModelTreeVO> nodes = response.getData();
            List<PlnApsResourceEvidence.Gap> gaps = new ArrayList<>();
            List<String> refs = new ArrayList<>();
            refs.add("MDM:FACTORY_MODEL:" + context.factoryCode());
            if (nodes.isEmpty()) {
                gaps.add(new PlnApsResourceEvidence.Gap("MDM_FACTORY_MODEL_EMPTY", "RESOURCE", "BLOCKER",
                        "FACTORY_MODEL", context.factoryCode(), "工厂资源模型为空",
                        "当前工厂没有车间、产线或工位层级，不能形成有限产能排程。",
                        "/mdm/factory-model/index", Map.of("factoryCode", context.factoryCode())));
            }
            if (context.productCodes().isEmpty()) {
                gaps.add(new PlnApsResourceEvidence.Gap("MDM_ENGINEERING_SCOPE_MISSING", "ENGINEERING", "BLOCKER",
                        "ROUTE_ENGINEERING", null, "产品工程范围尚未确定",
                        "案件来源未解析出产品编码，无法精确核验BOM、工艺路线、工时、技能、工装和换型约束。",
                        "/mdm/mes/processRoute/index", Map.of("caseId", context.caseId().toString())));
            }
            R<List<MdmCalendarEntity>> calendarResponse = calendarService.listByRange(
                    context.horizonStart().toLocalDate(), context.horizonEnd().toLocalDate());
            List<MdmCalendarEntity> calendar = calendarResponse != null && calendarResponse.isOk()
                    && calendarResponse.getData() != null ? calendarResponse.getData() : List.of();
            if (calendar.isEmpty()) {
                gaps.add(new PlnApsResourceEvidence.Gap("MDM_CALENDAR_EMPTY", "RESOURCE", "BLOCKER",
                        "WORK_CALENDAR", context.factoryCode(), "排产日历不可用",
                        "排产时域内没有可核验的工作日历，不能按默认全天可用计算。",
                        "/mdm/calendar/index", Map.of("factoryCode", context.factoryCode())));
            } else {
                refs.add("MDM:WORK_CALENDAR:" + context.factoryCode());
            }

            Map<String, List<MdmRouteEngineeringDTO.ProductionCandidate>> engineering = new java.util.TreeMap<>();
            if (context.productCodes().size() > 200) {
                gaps.add(new PlnApsResourceEvidence.Gap("MDM_ENGINEERING_SCOPE_TOO_LARGE", "ENGINEERING", "BLOCKER",
                        "ROUTE_ENGINEERING", null, "产品工程范围过大",
                        "单次案件包含超过200个产品，禁止截断工程版本后继续计算，请拆分排产范围。",
                        "/plan/plnSchedulingWorkbench/index", Map.of("productCount", context.productCodes().size())));
            }
            for (String productCode : context.productCodes().stream().limit(200).toList()) {
                R<List<MdmRouteEngineeringDTO.ProductionCandidate>> candidates =
                        routeEngineeringService.productionCandidates(productCode);
                List<MdmRouteEngineeringDTO.ProductionCandidate> rows = candidates != null && candidates.isOk()
                        && candidates.getData() != null ? candidates.getData() : List.of();
                engineering.put(productCode, rows);
                if (rows.isEmpty()) {
                    gaps.add(new PlnApsResourceEvidence.Gap(
                            "MDM_ENGINEERING_MISSING_" + sha256(productCode).substring(0, 12), "ENGINEERING", "BLOCKER",
                            "ROUTE_ENGINEERING", productCode, "产品缺少已发布工程版本",
                            "产品没有可供PLAN选择的正式BOM、路线、参数与SOP工程包。",
                            "/mdm/mes/processRoute/index", Map.of("productCode", productCode)));
                } else {
                    rows.forEach(row -> refs.add("MDM:ENGINEERING_RELEASE:" + row.getReleaseId() + ':' + row.getContentHash()));
                }
            }
            Map<String, Object> summary = new java.util.LinkedHashMap<>();
            summary.put("factoryCode", context.factoryCode());
            summary.put("rootNodeCount", nodes.size());
            summary.put("nodes", nodes);
            summary.put("calendar", calendar);
            summary.put("engineeringCandidates", engineering);
            String version = stableFingerprint(summary);
            return evidence(true, gaps.isEmpty() ? "COMPLETE" : "PARTIAL", version, LocalDateTime.now(),
                    summary, refs, gaps);
        } catch (Exception e) {
            return unavailable(true, "MDM_FACTORY_MODEL_CALL_FAILED", "RESOURCE", "FACTORY_MODEL",
                    "工厂资源模型调用失败", e, "/mdm/factory-model/index");
        }
    }
}
