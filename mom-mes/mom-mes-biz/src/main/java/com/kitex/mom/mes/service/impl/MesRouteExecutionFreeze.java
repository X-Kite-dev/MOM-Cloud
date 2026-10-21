package com.kitex.mom.mes.service.impl;

import cn.hutool.json.JSONUtil;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mdm.support.MdmProcessTimingPolicy;
import com.kitex.mom.mdm.support.MdmRouteNodeTimingPolicy;
import com.kitex.mom.mes.dto.MdmProcessRouteWorkingDTO;
import com.kitex.mom.mes.entity.MesProduceTaskEntity;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 新下达的完整节点执行合同；只使用本次原路线快照，不查询当前工程来补历史。 */
final class MesRouteExecutionFreeze {
    static final String VERSION = "NODE_EXECUTION_V1";

    private MesRouteExecutionFreeze() {
    }

    /** 旧唯一图在本次下达时无损投影；未知工程属性、附件与正式SOP原文保留。 */
    static String prepare(String snapshot, List<MdmProcessRouteWorkingDTO> routes) {
        if (routes.stream().allMatch(row -> row.getRouteNodeCode() == null)) return snapshot;
        Map<String, String> nodeProcesses = new LinkedHashMap<>();
        Map<String, String> modes = new LinkedHashMap<>();
        for (var row : routes) {
            String process = process(row);
            require(row.getRouteNodeCode() != null && row.getRouteNodeCode().matches("RN-[1-9][0-9]{0,18}")
                            && process != null && !process.isBlank()
                            && row.getPreRouteNodeCodes() != null && row.getNextRouteNodeCodes() != null,
                    "下达路线节点身份或节点关系不完整");
            require(nodeProcesses.putIfAbsent(row.getRouteNodeCode(), process) == null,
                    "下达路线节点身份重复");
            modes.put(row.getRouteNodeCode(), row.getType());
        }
        boolean uniqueProcesses = nodeProcesses.values().stream().distinct().count() == nodeProcesses.size();
        Map<String, Set<String>> predecessors = new LinkedHashMap<>();
        for (var row : routes) {
            require(same(row.getPreRouteCode(), project(row.getPreRouteNodeCodes(), nodeProcesses))
                            && same(row.getNextRouteCode(), project(row.getNextRouteNodeCodes(), nodeProcesses)),
                    "冻结节点关系与主工序投影不一致");
            predecessors.put(row.getRouteNodeCode(),
                    new LinkedHashSet<>(Arrays.asList(row.getPreRouteNodeCodes())));
        }
        Map<String, MdmProcessRouteWorkingDTO> byNode = new LinkedHashMap<>();
        for (var row : routes) {
            String timing = row.getNodeTimingRulesJson();
            timing = MdmRouteNodeTimingPolicy.normalize(timing, Map.of(), row.getRouteNodeCode(),
                    MdmProcessTimingPolicy.ancestors(row.getRouteNodeCode(), predecessors), modes);
            if (uniqueProcesses) {
                // 一对一图仍核旧投影；重复主工序图只允许原生节点规则，旧字段必须为空。
                require(Objects.equals(MdmProcessTimingPolicy.parse(
                                        MdmRouteNodeTimingPolicy.toLegacy(timing, nodeProcesses)).rules(),
                                MdmProcessTimingPolicy.parse(row.getTimingRulesJson()).rules()),
                        "节点时间规则与主工序投影不一致");
            } else {
                require(row.getTimingRulesJson() == null || row.getTimingRulesJson().isBlank(),
                        "重复主工序图不能夹带有歧义的旧时间规则");
            }
            row.setNodeTimingRulesJson(timing);
            byNode.put(row.getRouteNodeCode(), row);
        }
        var original = JSONUtil.parseArray(snapshot);
        require(original.size() == routes.size(), "下达原图数量变化，不能缩减冻结范围");
        Set<String> visited = new LinkedHashSet<>();
        for (int i = 0; i < original.size(); i++) {
            var item = original.getJSONObject(i);
            var row = byNode.get(item.getStr("routeNodeCode"));
            require(row != null && visited.add(row.getRouteNodeCode()), "下达原图节点缺失或重复");
            item.set("routeExecutionSchemaVersion", VERSION);
            item.set("preRouteNodeCodes", row.getPreRouteNodeCodes());
            item.set("nextRouteNodeCodes", row.getNextRouteNodeCodes());
            item.set("nodeTimingRulesJson", row.getNodeTimingRulesJson());
            item.set("preRouteCode", row.getPreRouteCode());
            item.set("nextRouteCode", row.getNextRouteCode());
            item.set("orderNum", row.getOrderNum());
            item.set("routeOrder", row.getRouteOrder());
            item.set("timingRulesJson", row.getTimingRulesJson());
        }
        return original.toString();
    }

    static void apply(MdmProcessRouteWorkingDTO route, MesProduceTaskEntity target) {
        if (route.getRouteNodeCode() == null) return;
        require(route.getPreRouteNodeCodes() != null && route.getNextRouteNodeCodes() != null,
                "节点执行合同未准备完成");
        target.setRouteExecutionSchemaVersion(VERSION);
        target.setPredecessorNodeCodes(String.join(",", route.getPreRouteNodeCodes()));
        target.setSuccessorNodeCodes(String.join(",", route.getNextRouteNodeCodes()));
        target.setNodeTimingRulesJson(route.getNodeTimingRulesJson());
        target.setLastRoute(route.getNextRouteNodeCodes().length == 0);
    }

    private static String[] project(String[] values, Map<String, String> nodeProcesses) {
        if (values == null) return new String[0];
        return Arrays.stream(values).map(value -> {
            String process = nodeProcesses.get(value);
            require(process != null, "冻结关系指向原图外的路线节点：" + value);
            return process;
        }).toArray(String[]::new);
    }

    private static boolean same(String[] left, String[] right) {
        return left != null && right != null && Arrays.equals(left, right);
    }

    private static String process(MdmProcessRouteWorkingDTO row) {
        return row.getProcessCode() == null || row.getProcessCode().isBlank() ? row.getRouteCode() : row.getProcessCode();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }
}
