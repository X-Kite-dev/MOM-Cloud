package com.kitex.mom.mes.service.impl;

import com.kitex.mom.mes.dto.MesProcessCompositionDTO;
import com.kitex.mom.mes.entity.MesProduceTaskEntity;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A missing composite binding or summing sequential operations must break these fixtures.
 */
class MesProcessCompositionModelTest {

	@Test
	void twoLevelsBindBothRealOperationAndAssignmentToOneActivity() {
		var definition = definition(false, true);
		var nodes = MesProcessCompositionModel.bind(definition, List.of(task()), operations(), arrangements());

		assertEquals(3, nodes.size());
		var leaf = nodes.get(1);
		assertEquals("11", leaf.getProduceTaskId());
		assertEquals("21", leaf.getArrangeId());
		assertEquals(List.of("OPERATION", "ASSIGNMENT"), leaf.getBindings());
		assertEquals(nodes.get(0).getId(), leaf.getParentId());
	}

	@Test
	void fourLevelsRequireRealOrderAndBindBatchToExistingWorkOrder() {
		var nodes = MesProcessCompositionModel.bind(definition(true, false), List.of(task()),
				operations(), arrangements());

		assertEquals(6, nodes.size());
		assertEquals("PO-1", nodes.get(0).getSourceCode());
		assertEquals("1", nodes.get(1).getTaskId());
		assertEquals(nodes.get(0).getId(), nodes.get(1).getParentId());
		assertEquals("11", nodes.get(2).getProduceTaskId());
		assertEquals(nodes.get(2).getId(), nodes.get(4).getParentId());
		var missingOrder = task();
		missingOrder.setProductionOrder(null);
		assertThrows(RuntimeException.class, () -> MesProcessCompositionModel.bind(definition(true, false),
				List.of(missingOrder), operations(), arrangements()));
	}

	@Test
	void terminalQuantityIsCountedOnceAcrossSequentialOperationsAndLevels() {
		var operations = operations();
		operations.forEach(operation -> operation.setCompleteNum(BigDecimal.TEN));

		var progress = MesProcessCompositionModel.progress(List.of(task()), operations);

		assertEquals(1, progress.size());
		assertEquals(0, BigDecimal.TEN.compareTo(progress.get(0).getCompletedQuantity()));
		assertEquals(0, BigDecimal.TEN.compareTo(progress.get(0).getPlannedQuantity()));
	}

	@Test
	void malformedResponsibilityCompositionCannotBePublished() {
		var definition = definition(false, false);
		definition.getLevels().get(2).setBindings(List.of("OPERATION"));

		assertThrows(RuntimeException.class, () -> MesProcessCompositionModel.validate(definition));
	}

	@Test
	void missingTerminalCounterRemainsUnknownInsteadOfCompletedZero() {
		var operations = operations();
		operations.get(1).setCompleteNum(null);

		var progress = MesProcessCompositionModel.progress(List.of(task()), operations);

		assertNull(progress.get(0).getCompletedQuantity());
	}

	@Test
	void sharedDefinitionRulesRejectControlCharactersInDisplayLabels() {
		var definition = definition(false, false);
		definition.getLevels().get(0).setLabel("工单\n隐藏字段");

		assertThrows(RuntimeException.class, () -> MesProcessCompositionModel.validate(definition));
	}

	static MesProcessCompositionDTO.Definition definition(boolean order, boolean combined) {
		var definition = new MesProcessCompositionDTO.Definition();
		var levels = new java.util.ArrayList<MesProcessCompositionDTO.Level>();
		if (order) {
			levels.add(level("order", "订单", "ORDER"));
		}
		levels.add(level("task", order ? "部件/批次" : "生产工单", "TASK"));
		levels.add(level("operation", combined ? "作业任务" : "工序", combined
				? new String[]{"OPERATION", "ASSIGNMENT"} : new String[]{"OPERATION"}));
		if (!combined) {
			levels.add(level("assignment", "派工", "ASSIGNMENT"));
		}
		definition.setLevels(levels);
		return definition;
	}

	private static MesProcessCompositionDTO.Level level(String key, String label, String... bindings) {
		var level = new MesProcessCompositionDTO.Level();
		level.setKey(key);
		level.setLabel(label);
		level.setBindings(List.of(bindings));
		return level;
	}

	static MesTaskEntity task() {
		var task = new MesTaskEntity();
		task.setId(1L);
		task.setTenantId(1L);
		task.setFactoryCode("F1");
		task.setCode("T1");
		task.setProductionOrder("PO-1");
		task.setMatCode("M1");
		task.setNoticeNum(BigDecimal.TEN);
		task.setStatus("ISSUE");
		task.setProcessRouteCode("ROUTE-1");
		task.setProcessRouteId(101L);
		return task;
	}

	static List<MesProduceTaskEntity> operations() {
		var first = operation(11L, "P1", "RN-11", "", "RN-12", "", "PROC2", false);
		var last = operation(12L, "P2", "RN-12", "RN-11", "", "PROC1", "", true);
		return List.of(first, last);
	}

	private static MesProduceTaskEntity operation(long id, String code, String node, String previous,
			String next, String previousProcess, String nextProcess, boolean last) {
		var operation = new MesProduceTaskEntity();
		operation.setId(id);
		operation.setTenantId(1L);
		operation.setFactoryCode("F1");
		operation.setTaskCode("T1");
		operation.setCode(code);
		operation.setRouteCode(last ? "PROC2" : "PROC1");
		operation.setSourceRouteNodeCode(node);
		operation.setProcessRouteId(101L);
		operation.setProcessRouteCode("ROUTE-1");
		operation.setRouteExecutionSchemaVersion("NODE_EXECUTION_V1");
		operation.setPredecessorNodeCodes(previous);
		operation.setSuccessorNodeCodes(next);
		operation.setPredecessorCodes(previousProcess);
		operation.setSuccessorCodes(nextProcess);
		operation.setLastRoute(last);
		operation.setNoticeNum(BigDecimal.TEN);
		operation.setCompleteNum(BigDecimal.ZERO);
		operation.setWorksiteCodes("WS1");
		operation.setWorksiteNames("工位1");
		operation.setWorksiteTypes("MANUAL");
		operation.setStatus("WAIT");
		return operation;
	}

	static List<MesTaskArrangeEntity> arrangements() {
		var first = new MesTaskArrangeEntity();
		var last = new MesTaskArrangeEntity();
		org.springframework.beans.BeanUtils.copyProperties(operations().get(0), first);
		org.springframework.beans.BeanUtils.copyProperties(operations().get(1), last);
		first.setId(21L);
		first.setCode("A1");
		first.setProduceTaskCode("P1");
		last.setId(22L);
		last.setCode("A2");
		last.setProduceTaskCode("P2");
		return List.of(first, last);
	}
}
