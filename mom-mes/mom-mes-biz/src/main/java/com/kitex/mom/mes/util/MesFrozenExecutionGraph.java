package com.kitex.mom.mes.util;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mes.entity.MesProduceTaskEntity;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;

import java.math.BigDecimal;
import java.util.*;

/**
 * 仅使用原工单的完整实际任务集解释执行关系，不查询今日MDM。
 */
public final class MesFrozenExecutionGraph {
    public static final String NODE_EXECUTION_V1 = "NODE_EXECUTION_V1";

    private final boolean nodeContract;
    private final Map<String, MesProduceTaskEntity> tasks = new LinkedHashMap<>();
    private final Map<String, List<String>> predecessors = new LinkedHashMap<>();
    private final Map<String, List<String>> successors = new LinkedHashMap<>();
    private final List<String> topologicalOrder = new ArrayList<>();

    private MesFrozenExecutionGraph(List<MesProduceTaskEntity> rows) {
        require(rows != null && !rows.isEmpty(), "冻结执行图缺少原完整工序任务集");
        MesProduceTaskEntity first = rows.get(0);
        require(first != null, "冻结执行图包含空任务");
        nodeContract = NODE_EXECUTION_V1.equals(first.getRouteExecutionSchemaVersion());
        require(nodeContract || first.getRouteExecutionSchemaVersion() == null, "未知冻结执行图协议");
        require(first.getTenantId() != null && text(first.getFactoryCode()) && text(first.getTaskCode())
                && text(first.getProcessRouteCode()), "冻结执行图组织、工单或路线归属缺失");
        Set<Long> ids = new HashSet<>();
        Set<String> codes = new HashSet<>();
        for (MesProduceTaskEntity row : rows) {
            require(row != null && Objects.equals(first.getRouteExecutionSchemaVersion(), row.getRouteExecutionSchemaVersion()),
                    "冻结执行图不能混合新旧或未知协议");
            require(Objects.equals(first.getTenantId(), row.getTenantId())
                            && Objects.equals(first.getFactoryCode(), row.getFactoryCode())
                            && Objects.equals(first.getTaskCode(), row.getTaskCode())
                            && Objects.equals(first.getProcessRouteId(), row.getProcessRouteId())
                            && Objects.equals(first.getProcessRouteCode(), row.getProcessRouteCode()),
                    "冻结执行图必须属于同一组织、工单和路线");
            require(row.getId() != null && ids.add(row.getId()) && text(row.getCode()) && codes.add(row.getCode())
                    && text(row.getRouteCode()), "冻结执行图实际ID、任务编码或主工序缺失/重复");
            if (nodeContract) {
                require(node(row.getSourceRouteNodeCode()) && row.getPredecessorNodeCodes() != null
                        && row.getSuccessorNodeCodes() != null, "新冻结执行图缺少稳定节点或完整节点关系");
            } else {
                require(row.getPredecessorNodeCodes() == null && row.getSuccessorNodeCodes() == null
                        && row.getNodeTimingRulesJson() == null, "旧冻结任务不能夹带新执行图字段");
            }
            String key = rawKey(row);
            require(tasks.putIfAbsent(key, row) == null,
                    nodeContract ? "冻结执行图节点身份重复" : "旧冻结执行图主工序不唯一，不能桥接");
        }
        for (var entry : tasks.entrySet()) {
            MesProduceTaskEntity row = entry.getValue();
            List<String> previous = csv(nodeContract ? row.getPredecessorNodeCodes() : row.getPredecessorCodes());
            for (String code : previous) {
                require(tasks.containsKey(code) && !entry.getKey().equals(code), "冻结执行图前序悬空或自引用");
            }
            predecessors.put(entry.getKey(), previous);
        }
        if (nodeContract) {
            validateNodeGraph();
        }
    }

    public static MesFrozenExecutionGraph of(List<MesProduceTaskEntity> rows) {
        return new MesFrozenExecutionGraph(rows);
    }

    /**
     * 派工必须仍归属真实父任务；历史空节点不借父任务补身份。
     */
    public static void assertArrangement(MesProduceTaskEntity parent, MesTaskArrangeEntity arrange) {
        require(parent != null && arrange != null && parent.getTenantId() != null
                        && text(parent.getFactoryCode()) && text(parent.getTaskCode())
                        && text(parent.getCode()) && text(parent.getRouteCode())
                        && Objects.equals(parent.getTenantId(), arrange.getTenantId())
                        && Objects.equals(parent.getFactoryCode(), arrange.getFactoryCode())
                        && Objects.equals(parent.getTaskCode(), arrange.getTaskCode())
                        && Objects.equals(parent.getCode(), arrange.getProduceTaskCode())
                        && Objects.equals(parent.getRouteCode(), arrange.getRouteCode()),
                "派工与原实际工序的组织、工单或主工序不一致");
        String version = parent.getRouteExecutionSchemaVersion();
        require(Objects.equals(version, arrange.getRouteExecutionSchemaVersion())
                && (version == null || NODE_EXECUTION_V1.equals(version)), "派工与原实际工序执行协议不一致或未知");
        if (version == null) {
            require(parent.getPredecessorNodeCodes() == null && parent.getSuccessorNodeCodes() == null
                            && parent.getNodeTimingRulesJson() == null && arrange.getPredecessorNodeCodes() == null
                            && arrange.getSuccessorNodeCodes() == null && arrange.getNodeTimingRulesJson() == null,
                    "历史派工不能夹带新执行合同");
            require(arrange.getSourceRouteNodeCode() == null
                            || Objects.equals(parent.getSourceRouteNodeCode(), arrange.getSourceRouteNodeCode()),
                    "历史派工已有节点与原实际任务不一致");
            return;
        }
        require(node(parent.getSourceRouteNodeCode()) && parent.getPredecessorNodeCodes() != null
                        && parent.getSuccessorNodeCodes() != null && parent.getLastRoute() != null
                        && Objects.equals(parent.getSourceRouteNodeCode(), arrange.getSourceRouteNodeCode())
                        && Objects.equals(parent.getPredecessorNodeCodes(), arrange.getPredecessorNodeCodes())
                        && Objects.equals(parent.getSuccessorNodeCodes(), arrange.getSuccessorNodeCodes())
                        && Objects.equals(parent.getNodeTimingRulesJson(), arrange.getNodeTimingRulesJson())
                        && Objects.equals(parent.getPredecessorCodes(), arrange.getPredecessorCodes())
                        && Objects.equals(parent.getSuccessorCodes(), arrange.getSuccessorCodes())
                        && Objects.equals(parent.getRouteOrder(), arrange.getRouteOrder())
                        && Objects.equals(parent.getLastRoute(), arrange.getLastRoute())
                        && Objects.equals(parent.getOverlapEnabled(), arrange.getOverlapEnabled())
                        && sameQuantity(parent.getTransferBatchQuantity(), arrange.getTransferBatchQuantity())
                        && Objects.equals(parent.getTransferMinutes(), arrange.getTransferMinutes()),
                "新派工未完整复制原实际工序的冻结合同");
    }

    private static List<String> csv(String value) {
        if (value == null || value.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        for (String code : value.split(",", -1)) {
            String normalized = code.trim();
            require(text(normalized) && unique.add(normalized), "冻结执行关系包含空项或重复项");
            result.add(normalized);
        }
        return List.copyOf(result);
    }

    /**
     * 节点关系投影可因两个不同节点使用同一主工序而重复；节点关系本身仍由csv严格去重。
     */
    private static List<String> projectionCsv(String value) {
        if (value == null || value.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String code : value.split(",", -1)) {
            String normalized = code.trim();
            require(text(normalized) && Objects.equals(code, normalized),
                    "冻结主工序投影包含空项或首尾空格");
            result.add(normalized);
        }
        return List.copyOf(result);
    }

    private static boolean node(String value) {
        return value != null && value.length() <= 64 && value.matches("RN-[1-9][0-9]*");
    }

    private static boolean sameQuantity(BigDecimal first, BigDecimal second) {
        return first == null ? second == null : second != null && first.compareTo(second) == 0;
    }

    private static boolean text(String value) {
        return value != null && !value.isBlank();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new CheckedException(message);
        }
    }

    /** 直接后继；跨层直连不能用最长后裔深度代替，旧合同不推断。 */
    public List<MesProduceTaskEntity> successors(MesProduceTaskEntity current) {
        require(nodeContract, "直接后继需要完整冻结节点执行合同");
        return successors.get(key(current)).stream().map(tasks::get).toList();
    }

    public List<MesProduceTaskEntity> predecessors(MesProduceTaskEntity current) {
        String currentKey = key(current);
        return predecessors.get(currentKey).stream().map(tasks::get).toList();
    }

    /**
     * 按原节点身份解析时间来源，并验证它是目标的真实祖先（可以跨越中间工序）。
     * 不按主工序或拓扑层级猜测；并行但无祖先关系的节点不能充当计时来源。
     */
    public MesProduceTaskEntity ancestorNode(MesProduceTaskEntity target, String sourceRouteNodeCode) {
        require(nodeContract, "节点时间来源只能用于明确的节点执行合同");
        String targetKey = key(target);
        require(node(sourceRouteNodeCode) && !targetKey.equals(sourceRouteNodeCode),
                "时间来源必须是不同于目标的稳定节点");
        Set<String> ancestors = new HashSet<>();
        ArrayDeque<String> pending = new ArrayDeque<>(predecessors.get(targetKey));
        while (!pending.isEmpty()) {
            String current = pending.removeFirst();
            if (ancestors.add(current)) {
                pending.addAll(predecessors.get(current));
            }
        }
        require(ancestors.contains(sourceRouteNodeCode), "时间来源节点【" + sourceRouteNodeCode
                + "】不是目标节点【" + targetKey + "】的上游，禁止借用其他分支或本工序时间");
        return tasks.get(sourceRouteNodeCode);
    }

    /**
     * 返回目标的全部真实后裔任务及最长路径深度，不含目标自身。
     * 完整图已核DAG；按拓扑序每条边最多传播一次，分支汇合不重复展开路径。
     */
    public Map<String, Integer> descendantDepths(MesProduceTaskEntity target) {
        require(nodeContract, "节点后裔只能用于明确的节点执行合同");
        String targetKey = key(target);
        Map<String, Integer> depths = new LinkedHashMap<>();
        depths.put(targetKey, 0);
        for (String current : topologicalOrder) {
            Integer depth = depths.get(current);
            if (depth == null) continue;
            for (String next : successors.get(current)) {
                depths.merge(next, depth + 1, Math::max);
            }
        }
        Map<String, Integer> result = new LinkedHashMap<>();
        for (var entry : depths.entrySet()) {
            if (!targetKey.equals(entry.getKey())) {
                result.put(tasks.get(entry.getKey()).getCode(), entry.getValue());
            }
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * 从真实派工的父任务编码定位，不用主工序编码或拓扑顺序挑选一次执行。
     */
    public MesProduceTaskEntity current(MesTaskArrangeEntity arrange) {
        require(arrange != null, "当前派工缺失");
        MesProduceTaskEntity parent = tasks.values().stream()
                .filter(row -> Objects.equals(row.getCode(), arrange.getProduceTaskCode()))
                .findFirst().orElse(null);
        assertArrangement(parent, arrange);
        key(parent);
        return parent;
    }

    public String key(MesProduceTaskEntity current) {
        require(current != null, "当前工序任务缺失");
        String key = rawKey(current);
        MesProduceTaskEntity frozen = tasks.get(key);
        require(frozen != null && Objects.equals(frozen.getId(), current.getId())
                        && Objects.equals(frozen.getCode(), current.getCode())
                        && Objects.equals(frozen.getTenantId(), current.getTenantId())
                        && Objects.equals(frozen.getFactoryCode(), current.getFactoryCode())
                        && Objects.equals(frozen.getTaskCode(), current.getTaskCode())
                        && Objects.equals(frozen.getProcessRouteId(), current.getProcessRouteId())
                        && Objects.equals(frozen.getProcessRouteCode(), current.getProcessRouteCode())
                        && Objects.equals(frozen.getRouteCode(), current.getRouteCode())
                        && Objects.equals(frozen.getRouteExecutionSchemaVersion(), current.getRouteExecutionSchemaVersion())
                        && Objects.equals(frozen.getSourceRouteNodeCode(), current.getSourceRouteNodeCode())
                        && Objects.equals(frozen.getPredecessorCodes(), current.getPredecessorCodes())
                        && Objects.equals(frozen.getSuccessorCodes(), current.getSuccessorCodes())
                        && Objects.equals(frozen.getPredecessorNodeCodes(), current.getPredecessorNodeCodes())
                        && Objects.equals(frozen.getSuccessorNodeCodes(), current.getSuccessorNodeCodes())
                        && Objects.equals(frozen.getNodeTimingRulesJson(), current.getNodeTimingRulesJson())
                        && Objects.equals(frozen.getLastRoute(), current.getLastRoute()),
                "当前工序不属于原冻结执行图或合同已变化");
        return key;
    }

    private void validateNodeGraph() {
        Map<String, Integer> degrees = new LinkedHashMap<>();
        int endings = 0;
        for (var entry : tasks.entrySet()) {
            String key = entry.getKey();
            MesProduceTaskEntity row = entry.getValue();
            List<String> next = csv(row.getSuccessorNodeCodes());
            for (String code : next) {
                require(tasks.containsKey(code) && !key.equals(code), "冻结执行图后序悬空或自引用");
                require(predecessors.get(code).contains(key), "冻结节点前后序不是相互逆关系");
            }
            successors.put(key, next);
            degrees.put(key, predecessors.get(key).size());
            require(row.getLastRoute() != null && row.getLastRoute().equals(next.isEmpty()),
                    "冻结终点与原lastRoute标记不一致");
            if (next.isEmpty()) {
                endings++;
            }
            // 主工序字段继续旧语义；只能表示节点关系向主工序的投影。
            require(new LinkedHashSet<>(projectionCsv(row.getPredecessorCodes())).equals(project(predecessors.get(key)))
                            && new LinkedHashSet<>(projectionCsv(row.getSuccessorCodes())).equals(project(next)),
                    "冻结节点关系与原主工序关系投影不一致");
        }
        for (var entry : predecessors.entrySet()) {
            for (String previous : entry.getValue()) {
                require(successors.get(previous).contains(entry.getKey()), "冻结节点前后序不是相互逆关系");
            }
        }
        require(endings == 1, "冻结执行图必须有唯一终点");
        ArrayDeque<String> ready = new ArrayDeque<>();
        degrees.forEach((key, degree) -> {
            if (degree == 0) {
                ready.add(key);
            }
        });
        int visited = 0;
        while (!ready.isEmpty()) {
            String current = ready.removeFirst();
            visited++;
            topologicalOrder.add(current);
            for (String next : successors.get(current)) {
                int degree = degrees.compute(next, (key, old) -> old - 1);
                if (degree == 0) {
                    ready.addLast(next);
                }
            }
        }
        require(visited == tasks.size(), "冻结执行图存在环路");
    }

    private Set<String> project(List<String> nodes) {
        Set<String> result = new LinkedHashSet<>();
        for (String code : nodes) {
            result.add(tasks.get(code).getRouteCode());
        }
        return result;
    }

    private String rawKey(MesProduceTaskEntity row) {
        return nodeContract ? row.getSourceRouteNodeCode() : row.getRouteCode();
    }
}
