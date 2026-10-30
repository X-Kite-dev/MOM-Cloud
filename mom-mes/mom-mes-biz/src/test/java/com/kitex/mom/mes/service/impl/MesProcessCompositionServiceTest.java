package com.kitex.mom.mes.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.kitex.common.core.util.R;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.mom.common.seq.api.feign.RemoteSeqConfigService;
import com.kitex.mom.mes.batch.BatchGenerator;
import com.kitex.mom.mes.batch.BatchGeneratorService;
import com.kitex.mom.mes.dto.MesProcessCompositionDTO;
import com.kitex.mom.mes.dto.MesTaskArrangeDTO;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.mapper.MesProcessCompositionMapper;
import com.kitex.mom.mes.mapper.MesExecutionCommandCancellationMapper;
import com.kitex.mom.mes.service.MesExecutionTemplateService;
import com.kitex.mom.mes.service.MesIdentifierBindingGroupService;
import com.kitex.mom.mes.service.MesProduceTaskService;
import com.kitex.mom.mes.service.MesTaskService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.*;

/**
 * Real composition mapper SQL and JDBC transactions, calling the real arrange method.
 * Remote sequence, master-data policies and storage boundaries of the existing domain are adapted locally.
 */
class MesProcessCompositionServiceTest {
	private JdbcTemplate db;
	private MesProcessCompositionMapper mapper;
	private MesProcessCompositionService service;
	private MesExecutionCommandCancellationService cancellations;
	private MesTaskEntity task;
	private MesProcessCompositionDTO.Run command;
	private final AtomicInteger sequence = new AtomicInteger(20);

	@BeforeEach
	void setup() {
		var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
		source.setDriverClassName("org.h2.Driver");
		db = new JdbcTemplate(source);
		db.execute("CREATE SCHEMA mom_mes");
		db.execute("CREATE TABLE mom_mes.mes_process_composition_template(id bigint primary key, tenant_id bigint, "
				+ "factory_code varchar, code varchar, name varchar, version_no int, revision_no bigint, status varchar, "
				+ "definition_json varchar, create_by varchar, create_time timestamp, update_by varchar, update_time timestamp, "
				+ "del_flag varchar, unique(tenant_id,factory_code,code,version_no))");
		db.execute("CREATE TABLE mom_mes.mes_process_composition_run(id bigint primary key, tenant_id bigint, factory_code varchar, "
				+ "request_id varchar, request_fingerprint varchar, actor_id bigint, template_id bigint, template_version int, "
				+ "template_name varchar, definition_snapshot varchar, nodes_json varchar, task_codes_json varchar, "
				+ "create_by varchar, create_time timestamp, del_flag varchar, unique(tenant_id,factory_code,request_id))");
		db.execute("CREATE TABLE dispatch(id bigint primary key, payload varchar)");
		db.execute("CREATE TABLE source_task(id bigint primary key, status varchar)");
		db.update("INSERT INTO source_task VALUES(1,'ISSUE')");
		db.execute("CREATE TABLE request_lock(id bigint primary key)");
		db.update("INSERT INTO request_lock VALUES(1)");
		db.execute("CREATE TABLE mom_mes.mes_execution_command_cancellation(id bigint PRIMARY KEY,tenant_id bigint,"
				+ "factory_code varchar,command_kind varchar,request_id varchar,request_hash varchar,actor_id bigint,"
				+ "create_by varchar,create_time timestamp,del_flag varchar,UNIQUE(tenant_id,factory_code,command_kind,request_id))");
		var configuration = new Configuration();
		configuration.setMapUnderscoreToCamelCase(true);
		configuration.setEnvironment(new Environment("composition", new SpringManagedTransactionFactory(), source));
		configuration.addMapper(MesProcessCompositionMapper.class);
		configuration.addMapper(MesExecutionCommandCancellationMapper.class);
		var session = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration));
		var actualMapper = session.getMapper(MesProcessCompositionMapper.class);
		mapper = mock(MesProcessCompositionMapper.class, delegatesTo(actualMapper));
		doAnswer(call -> db.queryForObject("SELECT id FROM request_lock WHERE id=1 FOR UPDATE", Integer.class))
				.when(mapper).lock(any(), anyString(), anyString());
		var access = mock(MesActualFactoryAccessService.class);
		when(access.require(anyString())).thenReturn("F1");
		task = MesProcessCompositionModelTest.task();
		task.setFormalPlanContractId(201L);
		task.setEngineeringReleaseId(301L);
		task.setBatchRuleCode("TEST");
		var taskService = mock(MesTaskService.class);
		when(taskService.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenAnswer(call -> {
			task.setStatus(db.queryForObject("SELECT status FROM source_task WHERE id=1", String.class));
			return List.of(task);
		});
		when(taskService.update(any())).thenAnswer(call -> db.update("UPDATE source_task SET status='DISPATCH' WHERE id=1") == 1);
		var operations = mock(MesProduceTaskService.class);
		when(operations.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
				.thenAnswer(call -> MesProcessCompositionModelTest.operations());
		when(operations.update(any())).thenReturn(true);
		var locks = mock(MesIdentifierBindingGroupService.class);
		var arrange = mock(MesTaskArrangeServiceImpl.class, CALLS_REAL_METHODS);
		ReflectionTestUtils.setField(arrange, "actualFactoryAccess", access);
		ReflectionTestUtils.setField(arrange, "identifierBindingGroupService", locks);
		ReflectionTestUtils.setField(arrange, "mesTaskService", taskService);
		ReflectionTestUtils.setField(arrange, "produceTaskService", operations);
		var numbering = mock(RemoteSeqConfigService.class);
		when(numbering.create("mes_task_arrange")).thenAnswer(call -> R.ok((Object) ("A" + sequence.incrementAndGet())));
		ReflectionTestUtils.setField(arrange, "remoteSeqConfigService", numbering);
		var generator = new BatchGenerator() {
			public String generateBatchNumber(String order, String shift) {
				return order + ":" + shift;
			}

			public String getGeneratorType() {
				return "TEST";
			}

			public String getGeneratorName() {
				return "测试批次";
			}
		};
		ReflectionTestUtils.setField(arrange, "batchGeneratorService", new BatchGeneratorService(List.of(generator)));
		var executionTemplates = mock(MesExecutionTemplateService.class);
		doAnswer(call -> {
			((MesTaskArrangeEntity) call.getArgument(1)).setExecutionPolicySnapshot("{}");
			return null;
		}).when(executionTemplates).freezeForDispatch(any(), any());
		ReflectionTestUtils.setField(arrange, "executionTemplateService", executionTemplates);
		doAnswer(call -> db.queryForObject("SELECT count(*) FROM dispatch", Long.class)).when(arrange).count(any());
		doAnswer(call -> db.query("SELECT payload FROM dispatch ORDER BY id", (row, index) ->
				JSONUtil.toBean(row.getString(1), MesTaskArrangeEntity.class))).when(arrange)
				.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
		doAnswer(call -> {
			List<MesTaskArrangeEntity> rows = call.getArgument(0);
			for (var row : rows) {
				row.setId((long) sequence.incrementAndGet());
				db.update("INSERT INTO dispatch VALUES(?,?)", row.getId(), JSONUtil.toJsonStr(row));
			}
			return true;
		}).when(arrange).saveBatch(anyCollection());
		for (var entity : List.of(MesTaskEntity.class, com.kitex.mom.mes.entity.MesProduceTaskEntity.class,
				MesTaskArrangeEntity.class)) {
			TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "composition-domain"), entity);
		}
		var cancellationTarget = new MesExecutionCommandCancellationService(access,
				session.getMapper(MesExecutionCommandCancellationMapper.class));
		var cancellationProxy = new ProxyFactory(cancellationTarget);
		cancellationProxy.setProxyTargetClass(true);
		cancellationProxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),
				new AnnotationTransactionAttributeSource()));
		cancellations = (MesExecutionCommandCancellationService) cancellationProxy.getProxy();
		var target = new MesProcessCompositionService(access, mapper, taskService, operations, arrange, locks, cancellations);
		var proxy = new ProxyFactory(target);
		proxy.setProxyTargetClass(true);
		proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),
				new AnnotationTransactionAttributeSource()));
		service = (MesProcessCompositionService) proxy.getProxy();
		identity(7L);
		command = new MesProcessCompositionDTO.Run();
		command.setRequestId("composition-request-1");
		command.setTemplateId("101");
		command.setExpectedRevisionNo(1L);
		var dispatch = new MesTaskArrangeDTO();
		dispatch.setCode("T1");
		dispatch.setShiftCode("SHIFT-1");
		dispatch.setShiftName("白班");
		var first = new MesTaskArrangeEntity();
		first.setProduceTaskCode("P1");
		first.setWorkerCode("worker");
		first.setWorksiteCodes("WS1");
		var last = new MesTaskArrangeEntity();
		last.setProduceTaskCode("P2");
		last.setWorkerCode("worker");
		last.setWorksiteCodes("WS1");
		dispatch.setTaskArrangeList(List.of(first, last));
		command.setDispatches(List.of(dispatch));
		published(false, true);
	}

	@AfterEach
	void cleanup() {
		TenantContextHolder.clear();
		SecurityContextHolder.clearContext();
	}

	@Test
	void runCreatesRealDispatchAndPersistentCompositeBindings() {
		var view = service.run(command);

		assertEquals(2, db.queryForObject("SELECT count(*) FROM dispatch", Integer.class));
		assertEquals("DISPATCH", db.queryForObject("SELECT status FROM source_task WHERE id=1", String.class));
		assertEquals(1, db.queryForObject("SELECT count(*) FROM mom_mes.mes_process_composition_run", Integer.class));
		assertEquals(3, view.getNodes().size());
		assertEquals("11", view.getNodes().get(1).getProduceTaskId());
		assertNotNull(view.getNodes().get(1).getArrangeId());
		assertEquals(0, BigDecimal.TEN.compareTo(view.getProgress().get(0).getPlannedQuantity()));
	}

	@Test
	void sameOriginalRequestReturnsRunWithoutSecondDispatch() {
		var first = service.run(command);
		var replay = service.run(command);

		assertEquals(first.getId(), replay.getId());
		assertEquals(2, db.queryForObject("SELECT count(*) FROM dispatch", Integer.class));
		identity(8L);
		assertThrows(RuntimeException.class, () -> service.run(command));
	}

	@Test
	void failedBindingPersistenceRollsBackRealDispatchAndTaskState() {
		doAnswer(call -> {
			throw new IllegalStateException("binding write failed");
		}).when(mapper).insertRun(any());

		assertThrows(RuntimeException.class, () -> service.run(command));

		assertEquals(0, db.queryForObject("SELECT count(*) FROM dispatch", Integer.class));
		assertEquals("ISSUE", db.queryForObject("SELECT status FROM source_task WHERE id=1", String.class));
	}

	@Test
	void missingOrderStopsFourLevelsBeforeAnyDispatch() {
		published(true, false);
		task.setProductionOrder(null);

		assertThrows(RuntimeException.class, () -> service.run(command));

		assertEquals(0, db.queryForObject("SELECT count(*) FROM dispatch", Integer.class));
	}

	@Test
	void actualFourLevelRunPreservesSourceOrder() {
		published(true, false);

		var view = service.run(command);

		assertEquals(6, view.getNodes().size());
		assertEquals("PO-1", view.getNodes().get(0).getSourceCode());
		assertEquals(2, db.queryForObject("SELECT count(*) FROM dispatch", Integer.class));
	}

	@Test
	void existingUnpublishedOrCrossFactorySourceCannotBeDispatched() {
		task.setFactoryCode("F2");

		assertThrows(RuntimeException.class, () -> service.run(command));

		assertEquals(0, db.queryForObject("SELECT count(*) FROM dispatch", Integer.class));
	}

	@Test
	void savedAndPublishedConfigurationDrivesRealThreeLevelRun() {
		var draft = draft();
		var saved = service.saveDraft(draft);
		var published = service.publish(saved.getId(), saved.getRevisionNo());
		command.setTemplateId(published.getId());
		command.setExpectedRevisionNo(published.getRevisionNo());

		var view = service.run(command);

		assertEquals("PUBLISHED", service.templates().stream()
				.filter(row -> published.getId().equals(row.getId())).findFirst().orElseThrow().getStatus());
		assertEquals(5, view.getNodes().size());
		assertNull(view.getNodes().get(1).getArrangeId());
		assertEquals("11", view.getNodes().get(3).getProduceTaskId());
		assertEquals(2, db.queryForObject("SELECT count(*) FROM dispatch", Integer.class));
	}

	@Test
	void publishedVersionCannotBeEditedAndStaleDraftCannotOverwrite() {
		var draft = draft();
		var saved = service.saveDraft(draft);
		draft.setId(saved.getId());
		draft.setRevisionNo(saved.getRevisionNo());
		draft.setName("修订版本");
		var revised = service.saveDraft(draft);

		assertThrows(RuntimeException.class, () -> service.publish(saved.getId(), saved.getRevisionNo()));
		assertThrows(RuntimeException.class, () -> service.saveDraft(draft));

		service.publish(revised.getId(), revised.getRevisionNo());
		draft.setRevisionNo(revised.getRevisionNo() + 1);
		assertThrows(RuntimeException.class, () -> service.saveDraft(draft));
	}

	@Test
	void newVersionDoesNotReinterpretPreviousRunMapping() {
		var original = service.run(command);
		var draft = draft();
		draft.setCode("MODEL");
		draft.getDefinition().getLevels().get(0).setLabel("新名称");
		var saved = service.saveDraft(draft);
		service.publish(saved.getId(), saved.getRevisionNo());

		var restored = service.runView(original.getId());

		assertEquals(3, restored.getNodes().size());
		assertEquals("生产工单", restored.getDefinition().getLevels().get(0).getLabel());
		assertEquals(1, restored.getTemplateVersion());
	}

	@Test
	void changedPayloadCannotReuseAppliedOriginalRequest() {
		service.run(command);
		command.getDispatches().get(0).setShiftCode("SHIFT-2");

		assertThrows(RuntimeException.class, () -> service.run(command));

		assertEquals(2, db.queryForObject("SELECT count(*) FROM dispatch", Integer.class));
	}

	@Test
	void recoveryReadsOriginalReceiptWithoutDispatchingAnAbsentRequest() {
		assertEquals("ABSENT", service.receipt(command.getRequestId()).getStatus());
		assertEquals(0, db.queryForObject("SELECT count(*) FROM dispatch", Integer.class));

		var applied = service.run(command);
		var receipt = service.receipt(command.getRequestId());

		assertEquals("APPLIED", receipt.getStatus());
		assertEquals(applied.getId(), receipt.getRun().getId());
		identity(8L);
		assertThrows(RuntimeException.class, () -> service.receipt(command.getRequestId()));
	}

	@Test
	void rejectedOriginalCanBeCancelledThenCorrectedWithANewRequest() {
		command.getDispatches().get(0).getTaskArrangeList().get(0).setWorkerCode(null);
		assertThrows(RuntimeException.class, () -> service.run(command));
		assertEquals("ABSENT", service.receipt(command.getRequestId()).getStatus());

		assertEquals("CANCELLED", service.cancelOriginal(command).getStatus());
		assertEquals("CANCELLED", service.receipt(command.getRequestId()).getStatus());
		assertThrows(RuntimeException.class, () -> service.run(command));
		assertEquals(0, db.queryForObject("SELECT count(*) FROM dispatch", Integer.class));

		command.setRequestId("composition-corrected-2");
		command.getDispatches().get(0).getTaskArrangeList().get(0).setWorkerCode("worker");
		var corrected = service.run(command);

		assertEquals("composition-corrected-2", corrected.getRequestId());
		assertEquals(2, db.queryForObject("SELECT count(*) FROM dispatch", Integer.class));
	}

	@Test
	void cancelAppliedOriginalReturnsItsRunAndDoesNotWriteATombstone() {
		var original = service.run(command);

		var receipt = service.cancelOriginal(command);

		assertEquals("APPLIED", receipt.getStatus());
		assertEquals(original.getId(), receipt.getRun().getId());
		assertEquals(0, db.queryForObject("SELECT count(*) FROM mom_mes.mes_execution_command_cancellation", Integer.class));
		assertEquals(2, db.queryForObject("SELECT count(*) FROM dispatch", Integer.class));
	}

	@Test
	void lateExecuteWaitingForTheOriginalRequestLockCannotPassCommittedCancellation() throws Exception {
		var waiting = new java.util.concurrent.CountDownLatch(1);
		var allowExecute = new java.util.concurrent.CountDownLatch(1);
		doAnswer(call -> {
			if (Thread.currentThread().getName().equals("late-composition-execute")) {
				waiting.countDown();
				if (!allowExecute.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
					throw new IllegalStateException("original lock not released");
				}
			}
			return db.queryForObject("SELECT id FROM request_lock WHERE id=1 FOR UPDATE", Integer.class);
		}).when(mapper).lock(any(), anyString(), anyString());
		var outcome = new java.util.concurrent.atomic.AtomicReference<Throwable>();
		var late = new Thread(() -> {
			identity(7L);
			try {
				service.run(command);
			} catch (RuntimeException failure) {
				outcome.set(failure);
			} finally {
				cleanup();
			}
		}, "late-composition-execute");
		late.start();
		try {
			assertTrue(waiting.await(5, java.util.concurrent.TimeUnit.SECONDS));
			assertEquals("CANCELLED", service.cancelOriginal(command).getStatus());
		} finally {
			allowExecute.countDown();
			late.join(5000);
		}

		assertFalse(late.isAlive());
		assertNotNull(outcome.get());
		assertEquals(0, db.queryForObject("SELECT count(*) FROM dispatch", Integer.class));
		assertEquals("CANCELLED", service.receipt(command.getRequestId()).getStatus());
	}

	private MesProcessCompositionDTO.Draft draft() {
		var draft = new MesProcessCompositionDTO.Draft();
		draft.setCode("STANDARD");
		draft.setName("标准离散样板");
		draft.setDefinition(MesProcessCompositionModelTest.definition(false, false));
		return draft;
	}

	private void published(boolean order, boolean combined) {
		db.update("DELETE FROM mom_mes.mes_process_composition_template");
		db.update("INSERT INTO mom_mes.mes_process_composition_template(id,tenant_id,factory_code,code,name,version_no,"
				+ "revision_no,status,definition_json,create_time,del_flag) VALUES(101,1,'F1','MODEL','组合样板',1,1,'PUBLISHED',?,?,'0')",
				JSONUtil.toJsonStr(MesProcessCompositionModelTest.definition(order, combined)), LocalDateTime.now());
	}

	private void identity(long id) {
		TenantContextHolder.setTenantId(1L);
		var user = new MomUser(id, "worker", 1L, null, null, "执行员", "执行员", null, 1L,
				"password", true, true, "0", true, true, List.of());
		SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, "n/a", List.of()));
	}
}
