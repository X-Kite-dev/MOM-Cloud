package com.kitex.mom.mes.service.impl;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mdm.support.MdmProcessTimingPolicy;
import com.kitex.mom.mdm.support.MdmRouteNodeTimingPolicy;
import com.kitex.mom.mes.dto.MdmProcessRouteWorkingDTO;
import com.kitex.mom.mes.entity.MesTaskEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 只读重建普通工单原包经既有下达算法形成的执行路线投影。
 * 下达会规范化拓扑层级、时间规则并追加正式SOP；不能把这些合法追加误判为原工程漂移。
 */
@Component
@RequiredArgsConstructor
public class MesIssuedEngineeringSource {
	private final MesEngineeringPackageService packages;
	private final MesSopFreezeService sops;

	/**
	 * 从保存的原联合包重建期望值并核对；不查询今日主数据，不修改原任务或持久快照。
	 */
	public void validate(MesTaskEntity task) {
		var original = packages.readIssuedOriginal(task);
		var routes = packages.executionRoutes(original);
		normalizeNodes(routes);
		String expected = MesRouteExecutionFreeze.prepare(original.routeSnapshot(), routes);
		sops.freezeFromEngineeringSnapshot(task, routes, original.sopManifestSnapshot());
		expected = sops.mergeFrozenSnapshot(expected, routes);
		require(packages.sameProjection(task.getProcessRouteSnapshot(), expected)
						&& packages.sameProjection(task.getBomSnapshot(), original.bomSnapshot()),
				"原工单下达路线或BOM与保存的原工程不一致");
	}

	/**
	 * 仅核正式稳定节点图，按原下达normalizeRouteGraph的节点分支重建后序、主工序投影和拓扑层级。
	 * 只操作新反序列化的内存DTO；legacy图、循环、悬空或多终点不猜测修复。
	 */
	private void normalizeNodes(List<MdmProcessRouteWorkingDTO> routes) {
		require(routes != null && !routes.isEmpty() && routes.size() <= 200, "原正式路线节点范围缺失或超限");
		Map<String, MdmProcessRouteWorkingDTO> nodes = new LinkedHashMap<>();
		Map<String, Set<String>> predecessors = new LinkedHashMap<>();
		Map<String, Set<String>> successors = new LinkedHashMap<>();
		Map<String, String> processes = new LinkedHashMap<>();
		Map<String, String> modes = new HashMap<>();
		for (var row : routes) {
			String code = row.getRouteNodeCode();
			require(code != null && code.matches("RN-[1-9][0-9]{0,18}") && nodes.putIfAbsent(code, row) == null
							&& row.getProcessCode() != null && !row.getProcessCode().isBlank()
							&& row.getPreRouteNodeCodes() != null && row.getNextRouteNodeCodes() != null,
					"原正式节点身份或关系不完整");
			processes.put(code, row.getProcessCode());
			modes.put(code, row.getType());
			predecessors.put(code, new LinkedHashSet<>());
			successors.put(code, new LinkedHashSet<>());
		}
		for (var row : routes) {
			String code = row.getRouteNodeCode();
			for (String previous : row.getPreRouteNodeCodes()) {
				require(nodes.containsKey(previous) && !code.equals(previous)
						&& predecessors.get(code).add(previous), "原节点前序悬空、自引用或重复");
				successors.get(previous).add(code);
			}
		}
		for (var row : routes) {
			require(new LinkedHashSet<>(Arrays.asList(row.getNextRouteNodeCodes())).equals(
					successors.get(row.getRouteNodeCode())), "原节点前后序不互逆");
		}
		require(successors.values().stream().filter(Set::isEmpty).count() == 1, "原路线终点不唯一");
		Map<String, Integer> degree = new LinkedHashMap<>();
		Map<String, Integer> levels = new HashMap<>();
		Deque<String> queue = new ArrayDeque<>();
		predecessors.forEach((code, values) -> {
			degree.put(code, values.size());
			if (values.isEmpty()) {
				queue.add(code);
				levels.put(code, 1);
			}
		});
		int visited = 0;
		while (!queue.isEmpty()) {
			String code = queue.removeFirst();
			visited++;
			for (String next : successors.get(code)) {
				levels.merge(next, levels.get(code) + 1, Math::max);
				if (degree.compute(next, (key, value) -> value - 1) == 0) {
					queue.addLast(next);
				}
			}
		}
		require(visited == routes.size(), "原节点图存在环路");
		boolean uniqueProcesses = processes.values().stream().distinct().count() == routes.size();
		for (var row : routes) {
			String code = row.getRouteNodeCode();
			row.setPreRouteNodeCodes(predecessors.get(code).toArray(String[]::new));
			row.setNextRouteNodeCodes(successors.get(code).toArray(String[]::new));
			row.setPreRouteCode(predecessors.get(code).stream().map(processes::get).toArray(String[]::new));
			row.setNextRouteCode(successors.get(code).stream().map(processes::get).toArray(String[]::new));
			row.setOrderNum(levels.get(code));
			row.setRouteOrder(levels.get(code));
			row.setNodeTimingRulesJson(MdmRouteNodeTimingPolicy.normalize(row.getNodeTimingRulesJson(), Map.of(), code,
					MdmProcessTimingPolicy.ancestors(code, predecessors), modes));
			row.setTimingRulesJson(uniqueProcesses ? MdmRouteNodeTimingPolicy.toLegacy(row.getNodeTimingRulesJson(),
					processes) : null);
		}
	}

	private void require(boolean condition, String message) {
		if (!condition) {
			throw new CheckedException(message);
		}
	}
}
