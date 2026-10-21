package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import cn.hutool.json.JSONConfig;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mes.dto.MesIdentifierNodePolicyDTO;
import com.kitex.mom.mes.entity.MesExecutionTemplateEntity;
import com.kitex.mom.mes.entity.MesProduceTaskEntity;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.enums.ReportModeEnum;
import com.kitex.mom.mes.util.MesFrozenExecutionGraph;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 动态换码策略及真实工单区段校验，不读取实时模板猜测在制关系。
 *
 * <p>本组件只处理策略和实际工艺图；实时部署/渠道由独立就绪组件核定，
 * 活动实例仍由统一报工事务创建，不用模板动作或预览结果冒充执行许可。</p>
 */
@Component
public class MesIdentifierNodePolicyValidator {

    public static final String NODE_BINDING_V2 = "NODE_BINDING_V2";

    private static final Set<String> INPUT_FIELDS = Set.of("action", "bindingGroupCode", "externalNamespace",
            "bindRouteCode", "useRouteCodes", "releaseRouteCode", "inputMode", "externalCodePrefix",
            "externalCodeMinLength", "externalCodeMaxLength", "policySchemaVersion", "processRouteCode",
            "bindRouteNodeCode", "useRouteNodeCodes", "releaseRouteNodeCode");

    /** 独立严格解析器，不继承应用全局可能放宽的单引号、重复字段或尾随内容设置。 */
    private static final ObjectMapper POLICY_JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** 规范化草稿策略，拒绝客户端注入服务端指纹和工序事实。 */
    public MesIdentifierNodePolicyDTO normalize(String raw, MesExecutionTemplateEntity template) {
        if (StrUtil.isBlank(raw)) {
            return new MesIdentifierNodePolicyDTO();
        }
        require(raw.length() <= 160000, "节点标识策略必须是有界JSON对象");
        JsonNode json;
        try {
            json = POLICY_JSON.readTree(raw);
        } catch (IOException exception) {
            throw new CheckedException("节点标识策略不是合法JSON对象");
        }
        require(json != null && json.isObject(), "节点标识策略必须是JSON对象");
        json.fieldNames().forEachRemaining(key ->
                require(INPUT_FIELDS.contains(key), "节点策略包含未知字段或服务端冻结字段"));
        for (String key : INPUT_FIELDS) {
            JsonNode value = json.get(key);
            if (value == null || value.isNull()) {
                continue;
            }
            if ("useRouteCodes".equals(key) || "useRouteNodeCodes".equals(key)) {
                require(value.isArray(), "使用工序必须是文本编码数组");
                for (JsonNode route : value) {
                    require(route.isTextual(), "使用工序必须是文本编码数组");
                }
            } else if (key.endsWith("Length")) {
                require(value.isIntegralNumber() && value.canConvertToInt(),
                        "外码长度必须是精确整数，不接受文本、小数或超出整数范围的值");
            } else {
                require(value.isTextual(), "节点策略编码和规则必须是文本");
            }
        }
        MesIdentifierNodePolicyDTO parsed;
        try {
            parsed = POLICY_JSON.treeToValue(json, MesIdentifierNodePolicyDTO.class);
        } catch (IOException exception) {
            throw new CheckedException("节点标识策略字段类型不正确");
        }
        String action = StrUtil.blankToDefault(parsed.getAction(), "NONE").trim().toUpperCase(Locale.ROOT);
        boolean nativeNodes = nativePolicy(parsed);
        require(Set.of("NONE", "BIND", "USE", "RELEASE").contains(action), "节点动作只允许NONE/BIND/USE/RELEASE");
        if ("NONE".equals(action)) {
            require(!nativeNodes, "NONE不应携带动态节点协议，请清除原区段配置");
            return new MesIdentifierNodePolicyDTO();
        }
        require(template != null, "规范化动态节点策略缺少执行模板");
        require(ReportModeEnum.ONE_CODE.getCode().equals(template.getReportMode()), "动态节点绑定必须使用ONE_CODE单件报工");
        require(!"MAPPING_REQUIRED".equals(template.getIdentifierResolveMode()),
                "动态节点策略不能同时要求静态映射必转，请明确节点输入策略");
        String group = requiredCode(parsed.getBindingGroupCode(), "绑定组编码");
        String namespace = requiredCode(parsed.getExternalNamespace(), "外码命名空间");
        String bind = nativeNodes ? requiredNode(parsed.getBindRouteNodeCode())
                : requiredCode(parsed.getBindRouteCode(), "绑定工序");
        String release = nativeNodes ? requiredNode(parsed.getReleaseRouteNodeCode())
                : requiredCode(parsed.getReleaseRouteCode(), "释放工序");
        List<String> uses = normalizeCodes(nativeNodes ? parsed.getUseRouteNodeCodes() : parsed.getUseRouteCodes());
        if (nativeNodes) uses.forEach(this::requiredNode);
        require(!bind.equals(release) && !uses.contains(bind) && !uses.contains(release), "绑定、使用和释放节点不能重叠");
        requiredCode(template.getRouteCode(), "模板实际工序作用域");
        String processRoute = nativeNodes ? requiredCode(parsed.getProcessRouteCode(), "区段所属工艺路线") : null;
        if (nativeNodes) {
            require(Objects.equals(processRoute, template.getProcessRouteCode()), "节点区段与模板所属工艺路线不一致");
        }
        String route = nativeNodes ? requiredNode(template.getRouteNodeCode()) : template.getRouteCode();
        String expected = route.equals(bind) ? "BIND" : route.equals(release) ? "RELEASE" : uses.contains(route) ? "USE" : null;
        require(Objects.equals(action, expected), "模板工序不属于该区段或与节点动作不符");
        String input = StrUtil.blankToDefault(parsed.getInputMode(), "EXTERNAL_REQUIRED").trim().toUpperCase(Locale.ROOT);
        require(Set.of("EXTERNAL_REQUIRED", "EITHER").contains(input), "外码输入方式只允许EXTERNAL_REQUIRED/EITHER");
        int min = Objects.requireNonNullElse(parsed.getExternalCodeMinLength(), 1);
        int max = Objects.requireNonNullElse(parsed.getExternalCodeMaxLength(), 200);
        require(min >= 1 && max >= min && max <= 200, "外码长度须在1至200内且最大长度不能小于最小长度");
        String prefix = StrUtil.trimToNull(parsed.getExternalCodePrefix());
        require(prefix == null || (prefix.length() <= 80 && prefix.length() <= max
                && prefix.chars().noneMatch(Character::isISOControl)), "外码字面前缀包含控制字符或超过长度");
        MesIdentifierNodePolicyDTO result = new MesIdentifierNodePolicyDTO().setAction(action).setBindingGroupCode(group)
                .setExternalNamespace(namespace).setInputMode(input).setExternalCodePrefix(prefix)
                .setExternalCodeMinLength(min).setExternalCodeMaxLength(max);
        return nativeNodes ? result.setPolicySchemaVersion(NODE_BINDING_V2).setProcessRouteCode(processRoute)
                .setBindRouteNodeCode(bind).setUseRouteNodeCodes(uses).setReleaseRouteNodeCode(release)
                : result.setBindRouteCode(bind).setUseRouteCodes(uses).setReleaseRouteCode(release);
    }

    /** 仅持久化公开配置；服务端冻结字段不得从复制版本或旧草稿带回保存入口。 */
    public String configurationJson(MesIdentifierNodePolicyDTO policy) {
        return definitionJson(policy, true);
    }

    /** 发布时核当前工程真实归属；不将该实时目录用于解释任何历史派工。 */
    public void validateTemplateNodes(MesExecutionTemplateEntity template, String rawNodes) {
        JsonNode rows;
        try {
            rows = POLICY_JSON.readTree(rawNodes);
        } catch (Exception exception) {
            throw new CheckedException("无法读取当前工程路线的完整节点目录，请确认290升级及查询权限");
        }
        require(rows != null && rows.isArray() && !rows.isEmpty() && rows.size() <= 2000,
                "当前工艺路线无完整节点或超过2000个节点");
        Map<String, String> masters = new TreeMap<>();
        for (JsonNode row : rows) {
            String node = requiredNode(row.path("routeNodeCode").asText(null));
            String master = requiredCode(row.path("processCode").asText(null), "真实主工序");
            require(masters.putIfAbsent(node, master) == null, "工程目录包含重复节点");
        }
        require(Objects.equals(masters.get(template.getRouteNodeCode()), template.getRouteCode()),
                "模板节点不属于当前工程路线或与主工序不一致");
        MesIdentifierNodePolicyDTO policy = normalize(template.getIdentifierNodePolicy(), template);
        if (nativePolicy(policy)) {
            Set<String> roles = new HashSet<>(policy.getUseRouteNodeCodes());
            roles.add(policy.getBindRouteNodeCode());
            roles.add(policy.getReleaseRouteNodeCode());
            require(masters.keySet().containsAll(roles), "动态区段含当前工艺路线不存在的角色节点");
        }
    }

    /**
     * 根据真实工单规范化DAG冻结完整区段，支持区段内并行及汇合。
     *
     * <p>所有从绑定节点出发的区段路径必须到释放节点；不得从区段外直接进入使用节点。
     * 列表只是成员集合，不以工序序号或CSV位置代替真实边。</p>
     */
    public FrozenGroupFacts freeze(MesIdentifierNodePolicyDTO source, MesTaskEntity task,
                                   MesTaskArrangeEntity arrange, List<MesProduceTaskEntity> actualNodes) {
        require(source != null, "冻结节点策略不能为空");
        if ("NONE".equals(source.getAction())) {
            return new FrozenGroupFacts(new MesIdentifierNodePolicyDTO(), null, null, null);
        }
        require(task != null && arrange != null && actualNodes != null && !actualNodes.isEmpty()
                && actualNodes.size() <= 2000, "冻结换码区段缺少完整工单工序图或节点数量超过2000");
        require(Objects.equals(task.getCode(), arrange.getTaskCode())
                && Objects.equals(task.getFactoryCode(), arrange.getFactoryCode())
                && Objects.equals(task.getTenantId(), arrange.getTenantId()), "冻结区段的派工与工单组织归属不一致");
        boolean nativeNodes = nativePolicy(source);
        if (nativeNodes) {
            // 与 NODE_EXECUTION_V1 原图出生守卫一致，不能冻结数据库不接受的原生图。
            require(actualNodes.size() <= 1000, "原生节点执行图最多支持1000个节点");
            MesFrozenExecutionGraph graph = MesFrozenExecutionGraph.of(actualNodes);
            require(MesFrozenExecutionGraph.NODE_EXECUTION_V1.equals(actualNodes.get(0).getRouteExecutionSchemaVersion())
                            && Objects.equals(task.getProcessRouteCode(), source.getProcessRouteCode()),
                    "原生区段必须匹配原工单工程路线及完整节点执行合同");
            graph.current(arrange);
        }
        Map<String, MesProduceTaskEntity> nodes = new TreeMap<>();
        Map<String, Set<String>> predecessors = new TreeMap<>();
        Map<String, Set<String>> successors = new TreeMap<>();
        for (MesProduceTaskEntity node : actualNodes) {
            require(node != null && node.getId() != null && StrUtil.isNotBlank(node.getCode())
                    && StrUtil.isNotBlank(node.getRouteCode()) && Objects.equals(node.getTaskCode(), task.getCode())
                    && Objects.equals(node.getFactoryCode(), task.getFactoryCode())
                    && Objects.equals(node.getTenantId(), task.getTenantId()), "工序图含缺失身份或其他工单/组织节点");
            String key = nativeNodes ? node.getSourceRouteNodeCode() : node.getRouteCode();
            require(nodes.putIfAbsent(key, node) == null, "工单图身份重复；旧主工序策略不能解释重复节点");
            predecessors.put(key, csv(nativeNodes ? node.getPredecessorNodeCodes() : node.getPredecessorCodes()));
            successors.put(key, new TreeSet<>());
        }
        for (var entry : predecessors.entrySet()) {
            for (String before : entry.getValue()) {
                require(nodes.containsKey(before) && !before.equals(entry.getKey()), "工序图含缺失前序或自身依赖");
                successors.get(before).add(entry.getKey());
            }
        }
        for (var entry : nodes.entrySet()) {
            require(successors.get(entry.getKey()).equals(csv(nativeNodes ? entry.getValue().getSuccessorNodeCodes()
                    : entry.getValue().getSuccessorCodes())), "工序任务前后序事实不一致，禁止猜测换码区段");
        }
        assertAcyclic(predecessors, successors);
        String bind = nativeNodes ? source.getBindRouteNodeCode() : source.getBindRouteCode();
        String release = nativeNodes ? source.getReleaseRouteNodeCode() : source.getReleaseRouteCode();
        String current = nativeNodes ? arrange.getSourceRouteNodeCode() : arrange.getRouteCode();
        Set<String> declared = new TreeSet<>(nativeNodes ? source.getUseRouteNodeCodes() : source.getUseRouteCodes());
        declared.add(bind);
        declared.add(release);
        require(nodes.keySet().containsAll(declared) && declared.contains(current), "换码区段含工单中不存在的节点或派工不在本区段");
        MesProduceTaskEntity arrangedNode = nodes.get(current);
        require(Objects.equals(arrangedNode.getCode(), arrange.getProduceTaskCode()),
                "派工工序任务号与实际路线节点不一致，禁止冻结错误节点身份");
        String expectedAction = role(source, arrange);
        require(expectedAction.equals(source.getAction()), "派工实际节点角色与换码动作不一致");
        Set<String> region = regionUntilRelease(bind, release, successors);
        require(region.equals(declared), "绑定至释放间的实际节点与配置成员不一致，存在跳站或未声明支路");
        for (String route : region) {
            if (!route.equals(bind)) {
                require(region.containsAll(predecessors.get(route)), "使用/释放节点有绕过绑定的区段外入口");
            }
        }
        List<MesIdentifierNodePolicyDTO.Node> fullGraph = nodes.values().stream()
                .map(node -> snapshot(node, predecessors, successors, nodes, nativeNodes)).toList();
        String definitionSnapshot = definitionJson(source, false);
        String graphSnapshot = JSONUtil.toJsonStr(fullGraph);
        require(StrUtil.isNotBlank(task.getProcessRouteSnapshot()), "动态区段缺少原始工单路线来源，禁止补造冻结事实");
        source.setDefinitionFingerprint(sha256((nativeNodes ? "MES_NODE_GROUP_V2:" : "MES_NODE_GROUP_V1:") + definitionSnapshot))
                .setGraphFingerprint(sha256((nativeNodes ? "MES_NODE_GRAPH_V2:" : "MES_NODE_GRAPH_V1:") + graphSnapshot))
                .setSourceRouteFingerprint(sha256("MES_NODE_ROUTE_SOURCE_V1:"
                        + StrUtil.blankToDefault(task.getProcessRouteSnapshot(), "")))
                .setFrozenNodes(fullGraph.stream().filter(node -> region.contains(nativeNodes
                        ? node.getSourceRouteNodeCode() : node.getRouteCode())).toList());
        return new FrozenGroupFacts(source, definitionSnapshot, graphSnapshot, task.getProcessRouteSnapshot());
    }

    /** 完整图和原始来源必须一起交付永久账，不能仅保存区段成员而丢掉全图指纹的依据。 */
    public record FrozenGroupFacts(MesIdentifierNodePolicyDTO policy, String definitionSnapshot,
                                   String graphSnapshot, String sourceRouteSnapshot) {
    }

    private Map<String, Object> definition(MesIdentifierNodePolicyDTO policy, boolean includeAction) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (includeAction) {
            values.put("action", policy.getAction());
        }
        if ("NONE".equals(policy.getAction())) {
            return values;
        }
        values.put("bindingGroupCode", policy.getBindingGroupCode());
        values.put("externalNamespace", policy.getExternalNamespace());
        if (nativePolicy(policy)) {
            values.put("policySchemaVersion", NODE_BINDING_V2);
            values.put("processRouteCode", policy.getProcessRouteCode());
            values.put("bindRouteNodeCode", policy.getBindRouteNodeCode());
            values.put("useRouteNodeCodes", policy.getUseRouteNodeCodes());
            values.put("releaseRouteNodeCode", policy.getReleaseRouteNodeCode());
        } else {
            values.put("bindRouteCode", policy.getBindRouteCode());
            values.put("useRouteCodes", policy.getUseRouteCodes());
            values.put("releaseRouteCode", policy.getReleaseRouteCode());
        }
        values.put("inputMode", policy.getInputMode());
        values.put("externalCodePrefix", policy.getExternalCodePrefix());
        values.put("externalCodeMinLength", policy.getExternalCodeMinLength());
        values.put("externalCodeMaxLength", policy.getExternalCodeMaxLength());
        return values;
    }

    /** 收集绑定至释放的闭合区段；未声明分支或提前终止路径不得静默忽略。 */
    private Set<String> regionUntilRelease(String bind, String release, Map<String, Set<String>> successors) {
        Set<String> visited = new TreeSet<>();
        ArrayDeque<String> pending = new ArrayDeque<>();
        pending.add(bind);
        while (!pending.isEmpty()) {
            String current = pending.removeFirst();
            if (!visited.add(current) || current.equals(release)) {
                continue;
            }
            require(!successors.get(current).isEmpty(), "绑定区段存在无法到达释放节点的终止路径");
            pending.addAll(successors.get(current));
        }
        require(visited.contains(release), "绑定节点无法到达释放节点");
        return visited;
    }

    /** 全图拓扑校验，防止后续区段遍历把循环误认为可结束流程。 */
    private void assertAcyclic(Map<String, Set<String>> predecessors, Map<String, Set<String>> successors) {
        Map<String, Integer> degrees = new TreeMap<>();
        predecessors.forEach((route, before) -> degrees.put(route, before.size()));
        ArrayDeque<String> ready = new ArrayDeque<>();
        degrees.forEach((route, degree) -> {
            if (degree == 0) {
                ready.add(route);
            }
        });
        int count = 0;
        while (!ready.isEmpty()) {
            String route = ready.removeFirst();
            count++;
            for (String next : successors.get(route)) {
                int degree = degrees.compute(next, (key, value) -> value - 1);
                if (degree == 0) {
                    ready.add(next);
                }
            }
        }
        require(count == predecessors.size(), "工单工序图有循环，禁止冻结换码区段");
    }

    private MesIdentifierNodePolicyDTO.Node snapshot(MesProduceTaskEntity node,
                                                     Map<String, Set<String>> before,
                                                     Map<String, Set<String>> after,
                                                     Map<String, MesProduceTaskEntity> nodes, boolean nativeNodes) {
        String key = nativeNodes ? node.getSourceRouteNodeCode() : node.getRouteCode();
        MesIdentifierNodePolicyDTO.Node result = new MesIdentifierNodePolicyDTO.Node().setProduceTaskId(node.getId().toString())
                .setProduceTaskCode(node.getCode()).setRouteCode(node.getRouteCode())
                .setPredecessorCodes(nativeNodes ? before.get(key).stream().map(code -> nodes.get(code).getRouteCode()).distinct().sorted().toList()
                        : List.copyOf(before.get(key)))
                .setSuccessorCodes(nativeNodes ? after.get(key).stream().map(code -> nodes.get(code).getRouteCode()).distinct().sorted().toList()
                        : List.copyOf(after.get(key)));
        return nativeNodes ? result.setSourceRouteNodeCode(key).setPredecessorNodeCodes(List.copyOf(before.get(key)))
                .setSuccessorNodeCodes(List.copyOf(after.get(key))) : result;
    }

    /** 新协议独立序列化；旧定义的空值和原字段顺序保持既有算法。 */
    private String definitionJson(MesIdentifierNodePolicyDTO policy, boolean includeAction) {
        Map<String, Object> values = definition(policy, includeAction);
        return nativePolicy(policy) ? JSONUtil.parse(values, JSONConfig.create().setIgnoreNullValue(false)).toString()
                : JSONUtil.toJsonStr(values);
    }

    /** 拒绝混合/未知协议，不能把损坏的新策略当作历史策略执行。 */
    public static boolean nativePolicy(MesIdentifierNodePolicyDTO policy) {
        require(policy != null, "节点策略缺失");
        boolean nativeNodes = NODE_BINDING_V2.equals(policy.getPolicySchemaVersion());
        require(nativeNodes || policy.getPolicySchemaVersion() == null, "未知节点绑定策略协议");
        if (nativeNodes) {
            require(policy.getBindRouteCode() == null && policy.getReleaseRouteCode() == null
                    && (policy.getUseRouteCodes() == null || policy.getUseRouteCodes().isEmpty()), "原生节点策略不能混入旧主工序角色");
        } else {
            require(policy.getProcessRouteCode() == null && policy.getBindRouteNodeCode() == null
                    && policy.getUseRouteNodeCodes() == null && policy.getReleaseRouteNodeCode() == null,
                    "历史策略不能夹带未声明的节点配置");
        }
        return nativeNodes;
    }

    /** 当前角色必须按策略原协议定位，实际任务号仍由调用者同时核定。 */
    public static String role(MesIdentifierNodePolicyDTO policy, MesTaskArrangeEntity arrange) {
        boolean nativeNodes = nativePolicy(policy);
        return roleAt(policy, nativeNodes ? arrange.getSourceRouteNodeCode() : arrange.getRouteCode());
    }

    private static String roleAt(MesIdentifierNodePolicyDTO policy, String key) {
        boolean nativeNodes = nativePolicy(policy);
        String bind = nativeNodes ? policy.getBindRouteNodeCode() : policy.getBindRouteCode();
        String release = nativeNodes ? policy.getReleaseRouteNodeCode() : policy.getReleaseRouteCode();
        List<String> uses = nativeNodes ? policy.getUseRouteNodeCodes() : policy.getUseRouteCodes();
        require(key != null && bind != null && release != null && uses != null, "区段节点角色依据不完整");
        return key.equals(bind) ? "BIND" : key.equals(release) ? "RELEASE" : uses.contains(key) ? "USE" : null;
    }

    private String requiredNode(String value) {
        require(value != null && value.length() <= 64 && value.matches("RN-[1-9][0-9]*"), "节点编码必须是原工程路线的稳定RN身份");
        return value;
    }

    private Set<String> csv(String value) {
        if (StrUtil.isBlank(value)) {
            return new TreeSet<>();
        }
        return new TreeSet<>(normalizeCodes(Arrays.asList(value.split(",", -1))));
    }

    private List<String> normalizeCodes(List<String> values) {
        if (values == null) {
            return List.of();
        }
        require(values.size() <= 2000, "区段工序成员超过2000");
        List<String> normalized = new ArrayList<>();
        for (String value : values) {
            normalized.add(requiredCode(value, "区段工序编码"));
        }
        require(new HashSet<>(normalized).size() == normalized.size(), "区段工序编码不能为空或重复");
        return normalized.stream().sorted().toList();
    }

    private String requiredCode(String value, String label) {
        String normalized = StrUtil.trimToNull(value);
        require(normalized != null && normalized.length() <= 100
                && normalized.chars().noneMatch(Character::isISOControl), label + "不能为空、含控制字符或超过100字符");
        return normalized;
    }

    /** 固定使用JDK必备算法，不为策略指纹引入额外运行依赖。 */
    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK未提供SHA-256，禁止生成不可靠的节点策略指纹", exception);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new CheckedException(message);
        }
    }
}
