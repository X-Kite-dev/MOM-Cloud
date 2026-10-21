package com.kitex.mom.plan.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.plan.dto.PlanAgentPurchaseOrderDTO;
import com.kitex.mom.plan.dto.PlanAgentSubstituteDecisionDTO;
import com.kitex.mom.plan.dto.PlanAgentTransferDecisionDTO;
import com.kitex.mom.plan.dto.PlanResourceLocalReplanDTO;
import com.kitex.mom.plan.dto.PlanSchedulingAgentDTO;
import com.kitex.mom.plan.dto.PlanShortageLocalReplanDTO;
import com.kitex.mom.plan.dto.PlnSchedulingScenarioRunDTO;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 固定命令白名单及规范化指纹；查询契约与原控制器的实际DTO完全相同。 */
@Component
public class PlanAgentCommandCodec {
    private final ObjectMapper mapper;

    public PlanAgentCommandCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** 从业务控制器真实参数冻结完整命令，不使用客户端声明的hash。 */
    public Frozen freeze(String code, String variant, Object command) {
        try {
            JsonNode node = mapper.valueToTree(command);
            String requestId = "PLAN_MRP_SUBSTITUTE_DECISION".equals(code)
                    ? node.path("decision").path("requestId").asText("")
                    : node.path("requestId").asText("");
            if (requestId.isBlank() || requestId.length() > 100) {
                throw new CheckedException("受控命令必须包含长度不超过100的永久请求号");
            }
            String frozen = mapper.writeValueAsString(command);
            String canonical = mapper.writeValueAsString(normalize(node));
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
            return new Frozen(code, variant, requestId, hash, frozen);
        } catch (CheckedException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new CheckedException("受控命令快照无法规范化");
        }
    }

    /** 回执核查重建执行器实际发送的固定DTO，包括由平台注入的审批身份。 */
    public Frozen fromLookup(LookupQuery query) throws Exception {
        ObjectNode payload = query.commandPayload().deepCopy();
        String rootRequest = payload.path("requestId").asText("");
        String nestedRequest = payload.path("decision").path("requestId").asText("");
        String permanent = rootRequest.isBlank() ? nestedRequest : rootRequest;
        if (!query.requestId().equals(permanent) || !rootRequest.isBlank() && !nestedRequest.isBlank() && !rootRequest.equals(nestedRequest)) {
            throw new CheckedException("核查请求号与冻结命令永久号不一致");
        }
        String code = query.toolCode();
        String variant = "EXECUTION_DEVIATION_REPLAN".equals(payload.path("actionMode").asText())
                ? "RESOURCE_REPLAN" : "APS";
        Class<?> type;
        switch (code) {
            case "PLAN_MRP_SUBSTITUTE_DECISION" -> type = PlanAgentSubstituteDecisionDTO.class;
            case "PLAN_MRP_TRANSFER_DECISION" -> type = PlanAgentTransferDecisionDTO.class;
            case "PLAN_MRP_PURCHASE_ORDER_SUBMIT" -> type = PlanAgentPurchaseOrderDTO.class;
            case "PLAN_SHORTAGE_LOCAL_REPLAN_PUBLISH" -> type = PlanShortageLocalReplanDTO.PublishCommand.class;
            case "PLAN_APS_SCENARIO_RUN" -> type = PlnSchedulingScenarioRunDTO.class;
            case "PLAN_APS_SCENARIO_SELECT" -> type = "RESOURCE_REPLAN".equals(variant)
                    ? PlanResourceLocalReplanDTO.SelectCommand.class : PlanSchedulingAgentDTO.SelectCommand.class;
            case "PLAN_APS_SCENARIO_PUBLISH" -> type = "RESOURCE_REPLAN".equals(variant)
                    ? PlanResourceLocalReplanDTO.PublishCommand.class : PlanSchedulingAgentDTO.PublishCommand.class;
            default -> throw new CheckedException("没有注册此永久命令核查契约");
        }
        if (code.startsWith("PLAN_MRP_")) {
            payload.put("businessType", "OMS_SALES_ORDER".equals(query.businessType()) ? "SALE_ORDER" : query.businessType());
            payload.put("businessNo", query.businessNo());
            variant = "MRP";
        } else if ("PLAN_SHORTAGE_LOCAL_REPLAN_PUBLISH".equals(code)) {
            variant = "SHORTAGE_REPLAN";
        }
        if ("PLAN_APS_SCENARIO_PUBLISH".equals(code) && "APS".equals(variant)) {
            JsonNode approval = query.approval();
            payload.set("agentApprovalId", approval.path("id"));
            payload.set("agentApprovalNo", approval.path("no"));
            payload.set("applicantCode", approval.path("applicantCode"));
            payload.set("approverCode", approval.path("approverCode"));
        }
        // 仅复制目标DTO声明的字段，与固定领域接口忽略扩展路由参数的语义一致。
        ObjectNode allowed = mapper.createObjectNode();
        mapper.getSerializationConfig().introspect(mapper.constructType(type)).findProperties()
                .forEach(property -> {
                    if (payload.has(property.getName())) {
                        allowed.set(property.getName(), payload.get(property.getName()));
                    }
                });
        Object typed = mapper.treeToValue(allowed, type);
        Frozen frozen = freeze(code, variant, typed);
        if (!query.requestId().equals(frozen.requestId())) {
            throw new CheckedException("嵌套业务请求号与审批永久请求号不一致");
        }
        return frozen;
    }

    private Object normalize(JsonNode value) {
        if (value.isObject()) {
            Map<String, Object> values = new TreeMap<>();
            value.fields().forEachRemaining(field -> values.put(field.getKey(), normalize(field.getValue())));
            return values;
        }
        if (value.isArray()) {
            List<Object> values = new ArrayList<>();
            value.forEach(item -> values.add(normalize(item)));
            return values;
        }
        if (value.isNumber()) {
            return value.decimalValue().stripTrailingZeros();
        }
        if (value.isNull()) {
            return null;
        }
        return value.isBoolean() ? value.booleanValue() : value.asText();
    }

    public record Frozen(String toolCode, String variant, String requestId, String hash, String snapshot) {
    }

    public record LookupQuery(String factoryCode, String toolCode, String requestId, String businessType,
                              String businessNo, ObjectNode commandPayload, JsonNode approval) {
    }
}
