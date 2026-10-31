package com.kitex.mom.mes.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.mom.common.seq.api.feign.RemoteSeqConfigService;
import com.kitex.mom.mes.batch.BatchGenerator;
import com.kitex.mom.mes.batch.BatchGeneratorService;
import com.kitex.mom.mes.dto.MesTaskArrangeDTO;
import com.kitex.mom.mes.entity.MesProduceTaskEntity;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.mapper.MesTaskArrangeMapper;
import com.kitex.mom.mes.service.MesExecutionTemplateService;
import com.kitex.mom.mes.service.MesProduceTaskService;
import com.kitex.mom.mes.service.MesTaskService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/** 实际开工、复工、补派工及PAD投影；仅替换持久化与外部门禁，不启动数据库。 */
class MesTaskArrangeRootStateTest {

	@AfterEach
	void clearContext() {
		new MesStartContractGuardTest().clearContext();
	}

	@Test
	void firstSuccessfulStartPromotesOnlyItsLockedRootToProduction() throws Exception {
		var f = new Fixture("DISPATCH");
		f.main.setStartTime(LocalDateTime.of(2026, 10, 30, 8, 0));
		f.main.setCompleteNum(BigDecimal.ONE);

		f.service.start(11L);

		assertEquals("PRODUCT", f.arrange.getStatus());
		assertEquals("PRODUCT", f.writtenRootStatus());
		f.assertRootScope("DISPATCH");
		assertFalse(f.rootUpdates.get(0).getSqlSet().contains("start_time"));
		assertFalse(f.rootUpdates.get(0).getSqlSet().contains("complete_num"));
	}

	@Test
	void controlledResumePromotesLegacyDispatchedRootWithoutRewritingOriginalStart() throws Exception {
		var f = new Fixture("DISPATCH");
		f.arrange.setStatus("PAUSE");
		var original = LocalDateTime.of(2026, 10, 30, 8, 0);
		f.arrange.setStartTime(original);

		f.service.start(11L);

		assertEquals("PRODUCT", f.writtenRootStatus());
		assertEquals(original, f.arrange.getStartTime());
	}

	@Test
	void startingAnotherProcessKeepsRootAlreadyInProduction() throws Exception {
		var f = new Fixture("PRODUCT");

		f.service.start(11L);

		assertEquals("PRODUCT", f.writtenRootStatus());
		assertEquals("PRODUCT", f.arrange.getStatus());
	}

	@ParameterizedTest
	@ValueSource(strings = {"FINISH", "CLOSE"})
	void terminalRootCannotBeReopenedByStartOrResume(String status) throws Exception {
		for (String dispatchState : List.of("RECEIVE", "PAUSE")) {
			var f = new Fixture(status);
			f.arrange.setStatus(dispatchState);

			assertThrows(CheckedException.class, () -> f.service.start(11L));

			assertTrue(f.rootUpdates.isEmpty());
			assertEquals(dispatchState, f.arrange.getStatus());
		}
	}

	@Test
	void blockedMaterialGateCannotPromoteRoot() throws Exception {
		var f = new Fixture("DISPATCH");
		doThrow(new CheckedException("物料门阻断"))
				.when(f.base.dependency(MesMaterialStartGateService.class)).assertStart(any(), any());

		assertThrows(CheckedException.class, () -> f.service.start(11L));

		assertTrue(f.rootUpdates.isEmpty());
		assertEquals("RECEIVE", f.arrange.getStatus());
	}

	@Test
	void missingRootUpdateAbortsSuccessfulStartResult() throws Exception {
		var f = new Fixture("DISPATCH");
		f.rootUpdateSucceeds = false;

		assertThrows(CheckedException.class, () -> f.service.start(11L));

		assertEquals(1, f.rootUpdates.size());
	}

	@Test
	void firstDispatchRetainsDispatchedRootState() throws Exception {
		var f = new Fixture("ISSUE");

		f.dispatch();

		assertEquals("DISPATCH", f.writtenRootStatus());
		f.assertRootScope("ISSUE");
		assertEquals(1, f.savedDispatches.size());
	}

	@Test
	void supplementaryDispatchCannotDemoteRootAlreadyInProduction() throws Exception {
		var f = new Fixture("PRODUCT");

		f.dispatch();

		assertEquals("PRODUCT", f.writtenRootStatus());
		assertEquals(1, f.savedDispatches.size());
	}

	@ParameterizedTest
	@ValueSource(strings = {"FINISH", "CLOSE"})
	void dispatchCannotReopenTerminalRoot(String status) throws Exception {
		var f = new Fixture(status);

		assertThrows(CheckedException.class, f::dispatch);

		assertTrue(f.rootUpdates.isEmpty());
		assertTrue(f.savedDispatches.isEmpty());
	}

	@Test
	void currentTaskSeparatesSplitDispatchQuantityFromWholeOrderDemand() throws Exception {
		var f = new Fixture("PRODUCT");
		f.arrange.setStatus("PRODUCT").setStartTime(LocalDateTime.now());
		f.arrange.setNoticeNum(BigDecimal.ONE);
		f.main.setNoticeNum(new BigDecimal("2"));

		var current = f.service.getCurrentTask("S1", "SHOP", "W1");

		assertEquals(BigDecimal.ONE, BeanUtil.beanToMap(current).get("shiftNum"));
		assertEquals(new BigDecimal("2"), current.getNoticeNum());
		assertEquals(BigDecimal.ONE, f.arrange.getNoticeNum());
	}

	private static final class Fixture {
		final MesStartContractGuardTest.Fixture base = new MesStartContractGuardTest.Fixture(false, false);
		final MesTaskArrangeServiceImpl service = spy(base.start);
		final MesTaskArrangeEntity arrange = base.arrange;
		final MesTaskService tasks = base.dependency(MesTaskService.class);
		final MesProduceTaskService processes = base.dependency(MesProduceTaskService.class);
		final MesTaskEntity main = tasks.getOne(new LambdaQueryWrapper<MesTaskEntity>());
		final MesProduceTaskEntity process = processes.list(new LambdaQueryWrapper<MesProduceTaskEntity>()).get(0);
		final List<LambdaUpdateWrapper<MesTaskEntity>> rootUpdates = new ArrayList<>();
		final List<MesTaskArrangeEntity> savedDispatches = new ArrayList<>();
		boolean rootUpdateSucceeds = true;

		@SuppressWarnings("unchecked")
		Fixture(String rootStatus) throws Exception {
			TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "root-state-test"),
					MesProduceTaskEntity.class);
			main.setStatus(rootStatus).setBatchRuleCode("TEST").setWorkshopCode("SHOP");
			process.setWorksiteCodes("W1").setWorksiteNames("工位1").setWorksiteTypes("MANUAL");
			when(tasks.list(any(Wrapper.class))).thenReturn(List.of(main));
			when(tasks.update(any(Wrapper.class))).thenAnswer(call -> {
				rootUpdates.add(call.getArgument(0));
				return rootUpdateSucceeds;
			});
			when(processes.getOne(any(Wrapper.class))).thenReturn(process);
			when(processes.update(any(Wrapper.class))).thenReturn(true);
			when(base.dependency(MesTaskArrangeMapper.class).selectList(any(Wrapper.class)))
					.thenReturn(List.of(arrange));
			when(base.dependency(MesActualFactoryAccessService.class).require(anyString())).thenReturn("F1");
			when(base.dependency(RemoteSeqConfigService.class).create(anyString())).thenReturn(R.ok("A2"));
			var generator = mock(BatchGenerator.class);
			when(generator.generateBatchNumber(anyString(), anyString())).thenReturn("BATCH-1");
			when(base.dependency(BatchGeneratorService.class).getBatchGenerator("TEST")).thenReturn(generator);
			doAnswer(call -> {
				savedDispatches.addAll((Collection<MesTaskArrangeEntity>) call.getArgument(0));
				return true;
			}).when(service).saveBatch(anyCollection());
		}

		void dispatch() {
			ReflectionTestUtils.setField(service, "executionTemplateService", mock(MesExecutionTemplateService.class));
			var command = new MesTaskArrangeDTO();
			command.setCode("T1");
			command.setShiftCode("S2");
			command.setShiftName("晚班");
			command.setTaskArrangeList(List.of(new MesTaskArrangeEntity().setProduceTaskCode("P1")
					.setWorkerCode("worker-1").setWorkerName("员工1").setWorksiteCodes("W1")));
			service.arrange(command);
		}

		String writtenRootStatus() {
			if (rootUpdates.isEmpty()) {
				return main.getStatus();
			}
			var update = rootUpdates.get(rootUpdates.size() - 1);
			var status = Pattern.compile("status\\s*=\\s*#\\{ew\\.paramNameValuePairs\\.([^}]+)}")
					.matcher(update.getSqlSet());
			assertTrue(status.find(), update.getSqlSet());
			return (String) update.getParamNameValuePairs().get(status.group(1));
		}

		void assertRootScope(String originalStatus) {
			var update = rootUpdates.get(rootUpdates.size() - 1);
			String condition = update.getSqlSegment();
			for (String column : List.of("id", "tenant_id", "factory_code", "code", "status")) {
				assertTrue(Pattern.compile("\\b" + column + " = ").matcher(condition).find(), condition);
			}
			var values = update.getParamNameValuePairs().values();
			assertTrue(values.contains("F1"));
			assertTrue(values.contains("T1"));
			assertTrue(values.contains(originalStatus));
		}
	}
}
