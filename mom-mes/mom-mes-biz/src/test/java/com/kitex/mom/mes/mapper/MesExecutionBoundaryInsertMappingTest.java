package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.kitex.mom.mes.entity.MesProduceTaskEntity;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import org.apache.ibatis.mapping.BoundSql;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证真实 INSERT 映射保留起终点空串；NULL 仍只代表没有节点执行合同的旧记录。 */
class MesExecutionBoundaryInsertMappingTest {

	private static final Set<String> RELATIONS = Set.of("predecessorNodeCodes", "successorNodeCodes",
			"predecessorCodes", "successorCodes");

	private MybatisConfiguration configuration;

	@BeforeEach
	void setup() {
		configuration = new MybatisConfiguration();
		GlobalConfigUtils.setGlobalConfig(configuration, new GlobalConfig()
				.setDbConfig(new GlobalConfig.DbConfig().setInsertStrategy(FieldStrategy.NOT_EMPTY))
				.setIdentifierGenerator(ignored -> 1L));
		configuration.addMapper(MesProduceTaskMapper.class);
		configuration.addMapper(MesTaskArrangeMapper.class);
	}

	@ParameterizedTest(name = "{0} {1}")
	@MethodSource("boundaries")
	void insertsExplicitBoundaryMarkersWithNonEmptyGlobalStrategy(Mapping mapping, String scenario, String previousNode,
			String nextNode, String previousProcess, String nextProcess) {
		var task = task(mapping, previousNode, nextNode, previousProcess, nextProcess);
		configuration.newMetaObject(task).setValue("routeExecutionSchemaVersion", "NODE_EXECUTION_V1");

		var bound = insert(mapping, task);
		var values = writtenRelations(bound, task);

		assertEquals(Map.of("predecessorNodeCodes", previousNode, "successorNodeCodes", nextNode,
				"predecessorCodes", previousProcess, "successorCodes", nextProcess), values);
		for (String column : Set.of("predecessor_node_codes", "successor_node_codes", "predecessor_codes",
				"successor_codes")) {
			assertTrue(bound.getSql().contains(column), "INSERT 必须显式写入执行图边界列：" + column);
		}
	}

	@ParameterizedTest
	@MethodSource("mappings")
	void leavesAbsentLegacyRelationsNull(Mapping mapping) {
		var task = mapping.factory().get();
		var properties = configuration.newMetaObject(task);
		properties.setValue("id", 1L);
		properties.setValue("code", "legacy-task");

		var bound = insert(mapping, task);

		assertTrue(writtenRelations(bound, task).isEmpty(), "旧记录不能因全局写入策略被补造为空边界");
	}

	@ParameterizedTest
	@MethodSource("mappings")
	void doesNotRewriteFrozenNodeRelationsOnUpdate(Mapping mapping) {
		var task = task(mapping, "", "", "", "");
		configuration.newMetaObject(task).setValue("routeExecutionSchemaVersion", "NODE_EXECUTION_V1");

		var statement = configuration.getMappedStatement(mapping.mapper().getName() + ".updateById");
		var bound = statement.getBoundSql(Map.of("et", task));

		assertFalse(bound.getSql().contains("predecessor_node_codes"));
		assertFalse(bound.getSql().contains("successor_node_codes"));
	}

	private static Stream<Arguments> boundaries() {
		return mappings().flatMap(mapping -> Stream.of(
				Arguments.of(mapping, "start", "", "RN-20", "", "P20"),
				Arguments.of(mapping, "terminal", "RN-10", "", "P10", ""),
				Arguments.of(mapping, "single node", "", "", "", "")));
	}

	private static Stream<Mapping> mappings() {
		return Stream.of(new Mapping(MesProduceTaskMapper.class, MesProduceTaskEntity::new),
				new Mapping(MesTaskArrangeMapper.class, MesTaskArrangeEntity::new));
	}

	private Object task(Mapping mapping, String previousNode, String nextNode, String previousProcess,
			String nextProcess) {
		var task = mapping.factory().get();
		var properties = configuration.newMetaObject(task);
		properties.setValue("id", 1L);
		properties.setValue("code", "new-task");
		properties.setValue("predecessorNodeCodes", previousNode);
		properties.setValue("successorNodeCodes", nextNode);
		properties.setValue("predecessorCodes", previousProcess);
		properties.setValue("successorCodes", nextProcess);
		return task;
	}

	private BoundSql insert(Mapping mapping, Object task) {
		return configuration.getMappedStatement(mapping.mapper().getName() + ".insert").getBoundSql(task);
	}

	private record Mapping(Class<?> mapper, Supplier<?> factory) {

		@Override
		public String toString() {
			return mapper.getSimpleName();
		}
	}

	private Map<String, Object> writtenRelations(BoundSql bound, Object task) {
		var values = new LinkedHashMap<String, Object>();
		var properties = configuration.newMetaObject(task);
		for (var mapping : bound.getParameterMappings()) {
			if (RELATIONS.contains(mapping.getProperty())) {
				values.put(mapping.getProperty(), properties.getValue(mapping.getProperty()));
			}
		}
		return values;
	}
}
