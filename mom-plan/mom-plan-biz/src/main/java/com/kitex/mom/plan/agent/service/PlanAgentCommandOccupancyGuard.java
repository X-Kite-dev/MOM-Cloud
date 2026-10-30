package com.kitex.mom.plan.agent.service;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.pms.api.feign.RemotePmsAgentPurchaseService;
import com.kitex.mom.wms.api.feign.RemoteAllocationBillService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 独立于新proof账的旧业务永久号占用闸门，包括已逻辑删除的业务历史。
 * 所有SQL为固定业务schema/table/column，仅绑定已核验真实租户、工厂和请求号；
 * 不接受客户端表名、列名或任意SQL，网络/权限/账不可用不能解释为ABSENT。
 */
@Service
public class PlanAgentCommandOccupancyGuard {
    private final JdbcTemplate jdbc;
    private final RemoteAllocationBillService wms;
    private final RemotePmsAgentPurchaseService pms;

    public PlanAgentCommandOccupancyGuard(JdbcTemplate jdbc, RemoteAllocationBillService wms,
                                          RemotePmsAgentPurchaseService pms) {
        this.jdbc = jdbc;
        this.wms = wms;
        this.pms = pms;
    }

    /**
     * true只表示相关真实领域明确查无原请求，旧业务号占用不允许创建新意图冒认旧回执。
     */
    public boolean absent(PlanAgentCommandCodec.Frozen frozen, String factory) {
        Long tenant = TenantContextHolder.getTenantId();
        require(tenant != null && tenant > 0, "永久号占用核查缺少实际租户");
        String request = frozen.requestId();
        return switch (frozen.toolCode()) {
            case "PLAN_MRP_SUBSTITUTE_DECISION" -> count(
                    "SELECT count(*) FROM \"mom_plan\".plan_mat_mrp WHERE tenant_id=? AND factory_code=? "
                            + "AND (substitute_decision_request_id=? OR substitute_cancel_request_id=?)",
                    tenant, factory, request, request) == 0;
            case "PLAN_MRP_PURCHASE_ORDER_SUBMIT" -> count(
                    "SELECT count(*) FROM \"mom_plan\".plan_mat_mrp WHERE tenant_id=? AND factory_code=? AND purchase_request_id=?",
                    tenant, factory, request) == 0 && remoteAbsent(
                    pms.commandPresence(Map.of("factoryCode", factory, "requestId", request)), factory, request, "PMS");
            case "PLAN_MRP_TRANSFER_DECISION" -> remoteAbsent(
                    wms.transferPresence(Map.of("factoryCode", factory, "requestId", request)), factory, request, "WMS");
            case "PLAN_SHORTAGE_LOCAL_REPLAN_PUBLISH" -> count(
                    "SELECT count(*) FROM \"mom_plan\".pln_shortage_replan_case WHERE tenant_id=? AND factory_code=? AND publish_request_id=?",
                    tenant, factory, request) == 0;
            case "PLAN_APS_SCENARIO_RUN" -> count(
                    "SELECT count(*) FROM \"mom_plan\".pln_scheduling_scenario WHERE tenant_id=? AND factory_code=? AND request_id=?",
                    tenant, factory, request) == 0;
            case "PLAN_APS_SCENARIO_SELECT" -> count(
                    "SELECT count(*) FROM \"mom_plan\".pln_scheduling_scenario WHERE tenant_id=? AND factory_code=? AND selection_request_id=?",
                    tenant, factory, request) == 0;
            case "PLAN_APS_SCENARIO_PUBLISH" -> count(
                    "SELECT count(*) FROM \"mom_plan\".pln_scheduling_scenario WHERE tenant_id=? AND factory_code=? AND publish_request_id=?",
                    tenant, factory, request) == 0 && count(
                    "SELECT count(*) FROM \"mom_plan\".pln_resource_replan_case WHERE tenant_id=? AND factory_code=? AND publish_request_id=?",
                    tenant, factory, request) == 0;
            default -> throw new CheckedException("此永久命令没有部署原业务占用核查契约");
        };
    }

    private long count(String fixedSql, Object... parameters) {
        Long value = jdbc.queryForObject(fixedSql, Long.class, parameters);
        require(value != null, "领域历史永久号计数未得到权威结果");
        return value;
    }

    private boolean remoteAbsent(R<Map<String, String>> response, String factory, String request, String domain) {
        require(response != null && response.isOk() && response.getData() != null
                        && factory.equals(response.getData().get("factoryCode"))
                        && request.equals(response.getData().get("requestId"))
                        && ("ABSENT".equals(response.getData().get("status")) || "EXISTS".equals(response.getData().get("status"))),
                domain + "原永久号（含删除历史）尚不可权威核实");
        return "ABSENT".equals(response.getData().get("status"));
    }

    private void require(boolean valid, String message) {
        if (!valid) {
            throw new CheckedException(message);
        }
    }
}
