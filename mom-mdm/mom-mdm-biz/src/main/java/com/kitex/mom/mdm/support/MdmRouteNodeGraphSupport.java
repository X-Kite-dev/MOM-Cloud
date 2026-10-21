package com.kitex.mom.mdm.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mdm.entity.MdmProcessRouteWorkingEntity;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 独立路线节点图的规范化、旧唯一图编辑投影及画布引用重写。
 *
 * <p>主工序是能力主数据而非图的节点键。这里没有隐式顺序补线，客户端提交的前置节点
 * 为唯一权威；派生后置/层级后检查时间来源必须是祖先。所有方法只处理内存对象。</p>
 */
public final class MdmRouteNodeGraphSupport {

    public static final String VERSION = "ROUTE_NODE_GRAPH_V2";
    private static final Set<String> SENTINELS = Set.of("input", "output");

    private MdmRouteNodeGraphSupport() {
    }

    /** 协调后行携带本次客户端键；返回临时键到持久身份的完整映射。 */
    public static Map<String, String> normalize(List<MdmProcessRouteWorkingEntity> rows) {
        require(rows.size() <= 500, "单条路线最多维护500个节点");
        Map<String, String> keys = new LinkedHashMap<>();
        Map<String, MdmProcessRouteWorkingEntity> nodes = new LinkedHashMap<>();
        Map<String, String> modes = new HashMap<>();
        for (MdmProcessRouteWorkingEntity row : rows) {
            require(row != null && row.getRouteNodeCode() != null && row.getClientNodeKey() != null,
                    "路线节点身份未协调");
            require(keys.putIfAbsent(row.getClientNodeKey(), row.getRouteNodeCode()) == null
                    && nodes.putIfAbsent(row.getRouteNodeCode(), row) == null, "路线节点身份重复");
            modes.put(row.getRouteNodeCode(), row.getType());
            normalizeControls(row);
        }
        Map<String, LinkedHashSet<String>> predecessors = new LinkedHashMap<>();
        Map<String, LinkedHashSet<String>> successors = new LinkedHashMap<>();
        nodes.keySet().forEach(key -> {
            predecessors.put(key, new LinkedHashSet<>());
            successors.put(key, new LinkedHashSet<>());
        });
        for (MdmProcessRouteWorkingEntity row : rows) {
            String node = row.getRouteNodeCode();
            for (String key : codes(row.getPreRouteNodeCodes())) {
                String source = keys.get(key);
                require(source != null && !source.equals(node), "前置节点不在本图或引用自身：" + key);
                require(predecessors.get(node).add(source), "前置节点重复：" + key);
                successors.get(source).add(node);
            }
        }
        if (!rows.isEmpty()) {
            List<String> ends = successors.entrySet().stream().filter(entry -> entry.getValue().isEmpty())
                    .map(Map.Entry::getKey).toList();
            require(ends.size() == 1, "路线必须且只能有一个最终节点，请核对连线");
            require(!Boolean.TRUE.equals(nodes.get(ends.get(0)).getOverlapEnabled()),
                    "最终节点没有后序，不能启用转移批量重叠");
        }
        Map<String, Integer> degrees = new LinkedHashMap<>();
        Map<String, Integer> levels = new HashMap<>();
        ArrayDeque<String> ready = new ArrayDeque<>();
        predecessors.forEach((node, pre) -> {
            degrees.put(node, pre.size());
            if (pre.isEmpty()) {
                ready.add(node);
                levels.put(node, 1);
            }
        });
        int visited = 0;
        while (!ready.isEmpty()) {
            String node = ready.remove();
            visited++;
            for (String next : successors.get(node)) {
                levels.merge(next, levels.get(node) + 1, Math::max);
                if (degrees.compute(next, (key, degree) -> degree - 1) == 0) ready.add(next);
            }
        }
        require(visited == rows.size(), "路线节点存在环路，请检查前后关系");
        for (MdmProcessRouteWorkingEntity row : rows) {
            String node = row.getRouteNodeCode();
            row.setPreRouteNodeCodes(predecessors.get(node).toArray(String[]::new));
            row.setNextRouteNodeCodes(successors.get(node).toArray(String[]::new));
            row.setOrderNum(levels.get(node));
            row.setNodeTimingRulesJson(MdmRouteNodeTimingPolicy.normalize(row.getNodeTimingRulesJson(), keys,
                    node, MdmProcessTimingPolicy.ancestors(node, predecessors), modes));
        }
        projectLegacy(rows, false);
        return keys;
    }

    /** V2保存后，只有一对一主工序图才生成旧生产合同；重复图旧字段留空。 */
    private static void projectLegacy(List<MdmProcessRouteWorkingEntity> rows, boolean required) {
        Set<String> processes = new HashSet<>();
        boolean unique = rows.stream().allMatch(row -> processes.add(row.getProcessCode()));
        require(!required || unique,
                "该路线含重复主工序，旧默认路线接口不能唯一表达稳定节点；请发布工程联合版本并由PLAN绑定后投产");
        Map<String, String> nodeProcesses = new HashMap<>();
        rows.forEach(row -> nodeProcesses.put(row.getRouteNodeCode(), row.getProcessCode()));
        for (MdmProcessRouteWorkingEntity row : rows) {
            row.setPreRouteCode(unique ? mapCodes(row.getPreRouteNodeCodes(), nodeProcesses) : new String[0]);
            row.setNextRouteCode(unique ? mapCodes(row.getNextRouteNodeCodes(), nodeProcesses) : new String[0]);
            row.setTimingRulesJson(unique
                    ? MdmRouteNodeTimingPolicy.toLegacy(row.getNodeTimingRulesJson(), nodeProcesses) : null);
        }
    }

    /** 旧生产详情与选路共用此门；新工程读取不调用，不能静默选第一同名节点。 */
    public static void requireLegacyExecutable(String version, List<MdmProcessRouteWorkingEntity> rows) {
        Set<String> processes = new HashSet<>();
        require(rows.stream().allMatch(row -> processes.add(row.getProcessCode())),
                "该路线含重复主工序，旧默认路线接口不能唯一表达稳定节点；请发布工程联合版本并由PLAN绑定后投产");
        require(version == null || VERSION.equals(version), "未知路线图协议，当前生产服务不能读取");
        if (VERSION.equals(version)) {
            rows.forEach(row -> row.setClientNodeKey(row.getRouteNodeCode()));
            normalize(rows);
        }
    }

    /** 仅供工程编辑：把旧唯一主工序图显式投影到已存在的真实节点，不写数据库。 */
    public static Map<String, String> projectLegacyForEditing(List<MdmProcessRouteWorkingEntity> rows) {
        Map<String, String> processNodes = new LinkedHashMap<>();
        for (MdmProcessRouteWorkingEntity row : rows) {
            require(row.getId() != null && Objects.equals(row.getRouteNodeCode(), "RN-" + row.getId())
                            && processNodes.putIfAbsent(row.getProcessCode(), row.getRouteNodeCode()) == null,
                    "旧路线无法唯一映射到稳定节点，请核对升级数据");
        }
        for (MdmProcessRouteWorkingEntity row : rows) {
            row.setClientNodeKey(row.getRouteNodeCode());
            row.setPreRouteNodeCodes(mapCodes(row.getPreRouteCode(), processNodes));
            row.setNextRouteNodeCodes(mapCodes(row.getNextRouteCode(), processNodes));
            row.setNodeTimingRulesJson(MdmRouteNodeTimingPolicy.fromLegacy(row.getTimingRulesJson(), processNodes));
        }
        return processNodes;
    }

    /**
     * 重写VueFlow键，并核画布工作节点及工作连线与权威前序完全一致。
     * input/output只为视觉端点，不进入工艺节点，也不能成为中间跳转。
     */
    public static JsonNode canonicalCanvas(JsonNode snapshot, List<MdmProcessRouteWorkingEntity> rows,
            Map<String, String> keys) {
        if (snapshot == null || snapshot.isNull()) {
            require(rows.isEmpty(), "非空路线必须提供画布");
            return null;
        }
        require(snapshot.isObject() && snapshot.path("nodes").isArray() && snapshot.path("edges").isArray(),
                "路线画布不是有效的VueFlow快照");
        JsonNode result = snapshot.deepCopy();
        Map<String, MdmProcessRouteWorkingEntity> nodes = new HashMap<>();
        rows.forEach(row -> nodes.put(row.getRouteNodeCode(), row));
        Set<String> seen = new HashSet<>();
        for (JsonNode value : result.path("nodes")) {
            require(value instanceof ObjectNode && value.path("id").isTextual(), "画布节点ID无效");
            ObjectNode item = (ObjectNode) value;
            String id = resolveCanvasKey(item.path("id").asText(), keys);
            require(seen.add(id), "画布包含重复节点：" + id);
            item.put("id", id);
            if (SENTINELS.contains(id)) continue;
            MdmProcessRouteWorkingEntity row = nodes.get(id);
            require(row != null, "画布引用未知节点：" + id);
            ObjectNode data = item.path("data").isObject() ? (ObjectNode) item.get("data") : item.putObject("data");
            data.put("routeNodeCode", id);
            data.put("processCode", row.getProcessCode());
            data.put("clientNodeKey", id);
            data.put("newNode", false);
            data.put("id", String.valueOf(row.getId()));
        }
        Set<String> actual = new HashSet<>(seen);
        actual.removeAll(SENTINELS);
        require(actual.equals(nodes.keySet()), "画布与工序清单节点不一致，请刷新后核对");
        Set<String> edges = new HashSet<>();
        Set<String> expected = new HashSet<>();
        rows.forEach(row -> codes(row.getPreRouteNodeCodes()).forEach(pre -> expected.add(pre + ">" + row.getRouteNodeCode())));
        for (JsonNode value : result.path("edges")) {
            require(value instanceof ObjectNode && value.path("source").isTextual() && value.path("target").isTextual(),
                    "画布连线格式无效");
            ObjectNode edge = (ObjectNode) value;
            String from = resolveCanvasKey(edge.path("source").asText(), keys);
            String to = resolveCanvasKey(edge.path("target").asText(), keys);
            require(seen.contains(from) && seen.contains(to) && !from.equals(to)
                    && !"output".equals(from) && !"input".equals(to), "画布包含悬空或反向端点连线");
            if (!SENTINELS.contains(from) && !SENTINELS.contains(to)) {
                require(edges.add(from + ">" + to), "画布包含重复连线");
            } else if (nodes.containsKey(to)) {
                require(codes(nodes.get(to).getPreRouteNodeCodes()).isEmpty(), "开始端点只能连接无前序节点");
            } else if (nodes.containsKey(from)) {
                require(codes(nodes.get(from).getNextRouteNodeCodes()).isEmpty(), "结束端点只能连接最终节点");
            } else require(rows.isEmpty(), "非空路线不能绕过所有节点直接连接起止端点");
            edge.put("source", from);
            edge.put("target", to);
            edge.put("id", "edge-" + from + "-" + to);
        }
        require(edges.equals(expected), "画布连线与提交的前置节点关系不一致");
        return result;
    }

    private static String resolveCanvasKey(String value, Map<String, String> keys) {
        if (SENTINELS.contains(value)) return value;
        String node = keys.get(value);
        require(node != null, "画布节点键不在本次保存范围：" + value);
        return node;
    }

    private static String[] mapCodes(String[] source, Map<String, String> mapping) {
        return codes(source).stream().map(code -> {
            String mapped = mapping.get(code);
            require(mapped != null, "路线引用不存在或无法唯一投影：" + code);
            return mapped;
        }).toArray(String[]::new);
    }

    private static List<String> codes(String[] values) {
        if (values == null) return List.of();
        require(Arrays.stream(values).allMatch(value -> value != null && !value.isBlank() && value.equals(value.trim())),
                "节点引用不能包含空值或首尾空格");
        return Arrays.asList(values);
    }

    private static void normalizeControls(MdmProcessRouteWorkingEntity row) {
        row.setTimeCheck(Boolean.TRUE.equals(row.getTimeCheck()));
        if (!row.getTimeCheck()) row.setTimeVal(null);
        else require(row.getTimeVal() != null && row.getTimeVal().signum() >= 0, "转序分钟必须大于等于0");
        row.setOverlapEnabled(Boolean.TRUE.equals(row.getOverlapEnabled()));
        if (!row.getOverlapEnabled()) row.setTransferBatchQuantity(null);
        else require(row.getTransferBatchQuantity() != null && row.getTransferBatchQuantity().signum() > 0,
                "启用重叠执行后转移批量必须大于0");
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new CheckedException(message);
    }
}
