package com.kitex.mom.mes.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.kitex.common.core.util.R;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.mom.common.seq.api.feign.RemoteSeqConfigService;
import com.kitex.mom.mdm.entity.MdmClassesEntity;
import com.kitex.mom.mdm.feign.RemoteClassesService;
import com.kitex.mom.mes.batch.BatchGenerator;
import com.kitex.mom.mes.batch.BatchGeneratorService;
import com.kitex.mom.mes.dto.*;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.mapper.*;
import com.kitex.mom.mes.service.*;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.*;
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
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.*;

/**
 * Real annotated MyBatis SQL and Spring/H2 transactions invoke the existing arrange and retirement domain.
 */
class MesDispatchCorrectionServiceTest {

	private JdbcTemplate db;

	private MesDispatchCorrectionService service;

	private MesDispatchCorrectionMapper mapper;

	private MesTaskArrangeServiceImpl arrange;

	private MesDispatchReturnServiceImpl returns;

	private MesTaskArrangeMapper arrangeMapper;

	private MesProduceTaskMapper produceMapper;

	private DataSourceTransactionManager tx;

	private SqlSessionTemplate sql;

	private String frozen = "{\"identifierNodePolicy\":{\"action\":\"NONE\"},\"reportMode\":\"NO_CODE\"}";

	private boolean badPolicy;

	private boolean rejectProduceState;

	private boolean rejectTaskState;

	private final AtomicInteger ids = new AtomicInteger(1000);

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setup() {
		identity(7L);
		var source = new DriverManagerDataSource("jdbc:h2:mem:dispatch-correction-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000;INIT=CREATE SCHEMA IF NOT EXISTS mom_mes\\;SET SCHEMA mom_mes", "sa", "");
		source.setDriverClassName("org.h2.Driver");
		db = new JdbcTemplate(source);
		db.execute("CREATE SCHEMA IF NOT EXISTS mom_mes");
		db.execute("SET SCHEMA mom_mes");
		tx = new DataSourceTransactionManager(source);
		var config = new MybatisConfiguration();
		config.setMapUnderscoreToCamelCase(true);
		config.setEnvironment(new Environment("correction", new SpringManagedTransactionFactory(), source));
		for (Class<?> type : List.of(MesTaskArrangeMapper.class, MesTaskMapper.class, MesProduceTaskMapper.class, MesDispatchReturnRequestMapper.class, MesDispatchReturnOperationMapper.class, MesTaskReportMapper.class, MesWarehouseFeedMapper.class, MesTaskExecutionSegmentMapper.class, MesToolingExecutionMapper.class, MesChangeoverExecutionMapper.class, MesDispatchCorrectionMapper.class, MesExecutionCommandCancellationMapper.class)) {
			config.addMapper(type);
		}
		sql = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
		for (Class<?> type : List.of(MesTaskArrangeEntity.class, MesTaskEntity.class, MesProduceTaskEntity.class, MesDispatchReturnRequestEntity.class, MesDispatchReturnOperationEntity.class, MesTaskReportEntity.class, MesWarehouseFeedEntity.class, MesTaskExecutionSegmentEntity.class, MesToolingExecutionEntity.class, MesChangeoverExecutionEntity.class)) {
			createEntityTable(type);
		}
		createLedgerTable("mes_dispatch_correction_request", MesDispatchCorrectionRequestEntity.class);
		createLedgerTable("mes_dispatch_correction_operation", MesDispatchCorrectionOperationEntity.class);
		createLedgerTable("mes_execution_template", MesExecutionTemplateEntity.class);
		db.execute("CREATE TABLE mes_workpiece_time_session(id bigint,tenant_id bigint,factory_code varchar,arrange_id bigint)");
		db.execute("CREATE TABLE mes_execution_command_cancellation(id bigint primary key,tenant_id bigint,factory_code varchar,command_kind varchar,request_id varchar,request_hash varchar,actor_id bigint,create_by varchar,create_time timestamp,del_flag varchar,unique(tenant_id,factory_code,command_kind,request_id))");
		db.execute("CREATE TABLE request_lock(id bigint primary key)");
		db.update("INSERT INTO request_lock VALUES(1)");
		var actualMapper = sql.getMapper(MesDispatchCorrectionMapper.class);
		mapper = mock(MesDispatchCorrectionMapper.class, delegatesTo(actualMapper));
		doAnswer(call -> db.queryForObject("SELECT id FROM request_lock WHERE id=1 FOR UPDATE", Integer.class)).when(mapper).lock(anyLong(), anyString(), anyString());
		var access = mock(MesActualFactoryAccessService.class);
		when(access.require(anyString())).thenReturn("F1");
		var locks = mock(MesIdentifierBindingGroupService.class);
		doAnswer(call -> db.queryForObject("SELECT id FROM request_lock WHERE id=1 FOR UPDATE", Integer.class)).when(locks).lockDispatchTask(anyString());
		arrangeMapper = sql.getMapper(MesTaskArrangeMapper.class);
		produceMapper = sql.getMapper(MesProduceTaskMapper.class);
		var tasks = sql.getMapper(MesTaskMapper.class);
		var taskLock = new MesExecutionTaskLock(access, locks, arrangeMapper, sql.getMapper(MesTaskReportMapper.class));
		returns = mock(MesDispatchReturnServiceImpl.class, CALLS_REAL_METHODS);
		set(returns, "baseMapper", sql.getMapper(MesDispatchReturnRequestMapper.class));
		set(returns, "arrangeMapper", arrangeMapper);
		set(returns, "produceTaskMapper", produceMapper);
		set(returns, "taskMapper", tasks);
		set(returns, "reportMapper", sql.getMapper(MesTaskReportMapper.class));
		set(returns, "feedMapper", sql.getMapper(MesWarehouseFeedMapper.class));
		set(returns, "executionSegmentMapper", sql.getMapper(MesTaskExecutionSegmentMapper.class));
		set(returns, "toolingExecutionMapper", sql.getMapper(MesToolingExecutionMapper.class));
		set(returns, "changeoverExecutionMapper", sql.getMapper(MesChangeoverExecutionMapper.class));
		set(returns, "factoryAccess", access);
		set(returns, "taskLock", taskLock);
		set(returns, "bindingGroups", locks);
		set(returns, "terminalExecutionGuard", mock(MesTerminalExecutionGuard.class));
		set(returns, "correctionMapper", mapper);
		// New narrow ports are wired only when implementation exposes them; initial RED is missing behavior.
		if (ReflectionTestUtils.getField(returns, "baseMapper") == null) {
			throw new AssertionError("return mapper missing");
		}
		var taskService = mock(MesTaskService.class);
		when(taskService.list(any(Wrapper.class))).thenAnswer(c -> tasks.selectList(c.getArgument(0)));
		when(taskService.update(any(Wrapper.class))).thenAnswer(c -> !rejectTaskState && tasks.update(null, c.getArgument(0)) == 1);
		var produceService = mock(MesProduceTaskService.class);
		when(produceService.list(any(Wrapper.class))).thenAnswer(c -> produceMapper.selectList(c.getArgument(0)));
		when(produceService.update(any(Wrapper.class))).thenAnswer(c -> !rejectProduceState && produceMapper.update(null, c.getArgument(0)) > 0);
		arrange = mock(MesTaskArrangeServiceImpl.class, CALLS_REAL_METHODS);
		set(arrange, "baseMapper", arrangeMapper);
		set(arrange, "correctionMapper", mapper);
		set(arrange, "actualFactoryAccess", access);
		set(arrange, "identifierBindingGroupService", locks);
		set(arrange, "mesTaskService", taskService);
		set(arrange, "produceTaskService", produceService);
		set(arrange, "executionTaskLock", taskLock);
		set(arrange, "terminalExecutionGuard", mock(MesTerminalExecutionGuard.class));
		set(arrange, "dispatchReturnService", returns);
		var gate = mock(MesDeviceGateService.class);
		when(gate.check(any(), any(), any(), any())).thenReturn(new MesDeviceGateDecision().setAllowed(true));
		set(arrange, "deviceGateService", gate);
		set(arrange, "toolingExecutionMapper", sql.getMapper(MesToolingExecutionMapper.class));
		var sequence = mock(RemoteSeqConfigService.class);
		when(sequence.create("mes_task_arrange")).thenAnswer(c -> R.ok((Object) ("A" + ids.incrementAndGet())));
		set(arrange, "remoteSeqConfigService", sequence);
		set(arrange, "batchGeneratorService", new BatchGeneratorService(List.of(new BatchGenerator() {

			public String generateBatchNumber(String task, String shift) {
				return task + ":" + shift;
			}

			public String getGeneratorType() {
				return "TEST";
			}

			public String getGeneratorName() {
				return "测试";
			}
		})));
		var templates = mock(MesExecutionTemplateService.class);
		doAnswer(c -> {
			MesTaskArrangeEntity row = c.getArgument(1);
			row.setReportMode("NO_CODE");
			row.setExecutionPolicySnapshot(badPolicy ? "{\"reportMode\":\"CHANGED\"}" : frozen);
			return null;
		}).when(templates).freezeForDispatch(any(), any());
		set(arrange, "executionTemplateService", templates);
		doAnswer(c -> {
			Collection<MesTaskArrangeEntity> rows = c.getArgument(0);
			for (var row : rows) {
				row.setId((long) ids.incrementAndGet());
				row.setTenantId(1L);
				row.setDelFlag("0");
				assertEquals(1, arrangeMapper.insert(row));
			}
			return true;
		}).when(arrange).saveBatch(anyCollection());
		var shifts = mock(RemoteClassesService.class);
		var shift = new MdmClassesEntity();
		shift.setShiftCode("S1");
		shift.setShiftName("白班");
		shift.setStatus(true);
		shift.setStartTime(LocalTime.of(8, 0));
		shift.setEndTime(LocalTime.of(16, 0));
		shift.setId(51L);
		shift.setTenantId(1L);
		shift.setFactoryCode("F1");
		var second = BeanUtil.copyProperties(shift, MdmClassesEntity.class);
		second.setId(52L);
		second.setShiftCode("S2");
		second.setShiftName("晚班");
		when(shifts.listActive()).thenReturn(R.ok(List.of(shift, second)));
		var cancellations = proxy(new MesExecutionCommandCancellationService(access, sql.getMapper(MesExecutionCommandCancellationMapper.class)));
		service = proxy(new MesDispatchCorrectionService(access, mapper, returns, arrange, locks, cancellations, shifts));
		var task = new MesTaskEntity().setCode("T1").setStatus("ISSUE").setFactoryCode("F1").setBatchRuleCode("TEST").setWorkshopCode("SHOP").setNoticeNum(BigDecimal.TEN).setProcessRouteSnapshot("[{\"routeCode\":\"R1\",\"mdmProcessProcedureMoldList\":[]}]");
		task.setId(1L);
		task.setTenantId(1L);
		task.setDelFlag("0");
		tasks.insert(task);
		for (int i = 1; i <= 2; i++) {
			var produce = new MesProduceTaskEntity().setCode("P" + i).setTaskCode("T1").setStatus("WAIT").setRouteCode("R" + i).setRouteName("工序" + i).setWorksiteCodes("WS1,WS2").setWorksiteNames("一号,二号").setWorksiteTypes("MANUAL,MANUAL").setFactoryCode("F1").setNoticeNum(BigDecimal.TEN);
			produce.setId(10L + i);
			produce.setTenantId(1L);
			produce.setDelFlag("0");
			produceMapper.insert(produce);
		}
		var dispatch = new MesTaskArrangeDTO();
		dispatch.setCode("T1");
		dispatch.setShiftCode("S1");
		dispatch.setShiftName("白班");
		dispatch.setTaskArrangeList(List.of(new MesTaskArrangeEntity().setProduceTaskCode("P1").setWorkerCode("operator-7").setWorkerName("工人").setWorksiteCodes("WS1"), new MesTaskArrangeEntity().setProduceTaskCode("P2").setWorkerCode("operator-7").setWorkerName("工人").setWorksiteCodes("WS1")));
		new org.springframework.transaction.support.TransactionTemplate(tx).execute(s -> {
			arrange.arrange(dispatch);
			return null;
		});
	}

	@AfterEach
	void cleanup() {
		TenantContextHolder.clear();
		SecurityContextHolder.clearContext();
	}

	@Test
	void previewOfRealDispatchAllowsSameShiftWorksiteCorrectionDespiteSibling() {
		var preview = service.preview(originalId(), "S1", "WS2");
		assertTrue(preview.isEligible(), preview.getBlockers().toString());
		assertEquals("F1", preview.getFactoryCode());
		assertEquals(List.of("WS2"), preview.getTargetWorksiteCodes());
	}

	@Test
	void independentApprovalAtomicallyRetiresAndRunsRealArrangeWhilePreservingSibling() {
		var applied = submit();
		identity(8L);
		var approval = review(applied, "APPROVE");
		var result = service.command(approval);
		assertEquals("APPROVED", result.getResult().getCorrection().getStatus());
		assertEquals("CLOSE", mapper.arrange(1L, "F1", Long.valueOf(originalId())).getStatus());
		assertEquals(3, db.queryForObject("SELECT count(*) FROM mes_task_arrange", Integer.class));
		var replacement = mapper.replacement(1L, "F1", Long.valueOf(result.getResult().getCorrection().getId()));
		assertEquals("WS2", replacement.getWorksiteCodes());
		assertEquals("operator-7", replacement.getWorkerCode());
		assertEquals("S1", replacement.getShiftCode());
		assertEquals(result.getResult().getCorrection().getReplacementArrangeId(), replacement.getId().toString());
		assertEquals("APPLIED", service.receipt(approval.getRequestId()).getState());
		service.command(approval);
		assertEquals(3, db.queryForObject("SELECT count(*) FROM mes_task_arrange", Integer.class));
	}

	@Test
	void invalidReplacementPolicyRollsBackOldCloseAndRequiredAudit() {
		var submitted = submit();
		identity(8L);
		badPolicy = true;
		assertThrows(RuntimeException.class, () -> service.command(review(submitted, "APPROVE")));
		assertEquals("UN_RECEIVE", mapper.arrange(1L, "F1", Long.valueOf(originalId())).getStatus());
		assertEquals("SUBMITTED", service.detail(submitted.getResult().getCorrection().getId()).getCorrection().getStatus());
		assertEquals(2, db.queryForObject("SELECT count(*) FROM mes_task_arrange", Integer.class));
	}

	@Test
	void missingReplacementProcessStateWriteRollsBackApprovalAndOriginalClose() {
		var submitted = submit();
		identity(8L);
		rejectProduceState = true;

		assertThrows(RuntimeException.class, () -> service.command(review(submitted, "APPROVE")));
		assertEquals("UN_RECEIVE", mapper.arrange(1L, "F1", Long.valueOf(originalId())).getStatus());
		assertEquals("SUBMITTED", service.detail(submitted.getResult().getCorrection().getId()).getCorrection().getStatus());
		assertEquals(2, db.queryForObject("SELECT count(*) FROM mes_task_arrange", Integer.class));
	}

	@Test
	void missingReplacementWorkorderStateWriteRollsBackApprovalAndOriginalClose() {
		var submitted = submit();
		identity(8L);
		rejectTaskState = true;

		assertThrows(RuntimeException.class, () -> service.command(review(submitted, "APPROVE")));
		assertEquals("UN_RECEIVE", mapper.arrange(1L, "F1", Long.valueOf(originalId())).getStatus());
		assertEquals("SUBMITTED", service.detail(submitted.getResult().getCorrection().getId()).getCorrection().getStatus());
		assertEquals(2, db.queryForObject("SELECT count(*) FROM mes_task_arrange", Integer.class));
	}

	@Test
	void sameApplicantCannotApproveAndSourceDriftCannotApply() {
		var submitted = submit();
		assertThrows(RuntimeException.class, () -> service.command(review(submitted, "APPROVE")));
		identity(8L);
		db.update("UPDATE mes_task_arrange SET planned_start_time=? WHERE id=?", LocalDateTime.now(), Long.valueOf(originalId()));
		assertThrows(RuntimeException.class, () -> service.command(review(submitted, "APPROVE")));
		assertEquals(2, db.queryForObject("SELECT count(*) FROM mes_task_arrange", Integer.class));
	}

	@Test
	void ordinaryArrangeStillRejectsDuplicateTaskShift() {
		var dto = new MesTaskArrangeDTO();
		dto.setCode("T1");
		dto.setShiftCode("S1");
		assertThrows(RuntimeException.class, () -> new org.springframework.transaction.support.TransactionTemplate(tx).execute(s -> {
			arrange.arrange(dto);
			return null;
		}));
	}

	@Test
	void cancelledOriginalCannotLaterSubmitAndAppliedReceiptIsFrozen() {
		var preview = service.preview(originalId(), "S1", "WS2");
		var command = submitCommand(preview);
		assertEquals("CANCELLED", service.cancel(command).getState());
		assertThrows(RuntimeException.class, () -> service.command(command));
		assertEquals("CANCELLED", service.receipt(command.getRequestId()).getState());
		var submitted = submit();
		assertEquals("SUBMITTED", submitted.getResult().getCorrection().getStatus());
		identity(8L);
		service.command(review(submitted, "APPROVE"));
		identity(7L);
		assertEquals("SUBMITTED", service.receipt(submitted.getRequestId()).getResult().getCorrection().getStatus());
	}

	@Test
	void receivedRealChainAndUnreceivedParentCanBeCorrectedButExecutionFactsCannot() {
		new org.springframework.transaction.support.TransactionTemplate(tx).execute(s -> {
			arrange.receive(Long.valueOf(originalId()), "WS1");
			return null;
		});
		assertEquals("UN_RECEIVE", produceMapper.selectById(11L).getStatus());
		assertTrue(service.preview(originalId(), "S1", "WS2").isEligible());
		db.update("INSERT INTO mes_workpiece_time_session VALUES(1,1,'F1',?)", Long.valueOf(originalId()));
		assertFalse(service.preview(originalId(), "S1", "WS2").isEligible());
	}

	@Test
	void unknownFieldsAreRejectedEvenWhenGlobalJacksonIgnoresUnknownProperties() {
		var json = new com.fasterxml.jackson.databind.ObjectMapper().disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
		assertThrows(Exception.class, () -> json.readValue("{\"requestId\":\"12345678\",\"workerCode\":\"attacker\"}", MesDispatchCorrectionDTO.Command.class));
	}

	@Test
	void approvalCannotCarryChangedTargetAndPendingApplicationBlocksRealReceive() {
		var submitted = submit();
		assertThrows(RuntimeException.class, () -> new org.springframework.transaction.support.TransactionTemplate(tx).execute(s -> {
			arrange.receive(Long.valueOf(originalId()), "WS1");
			return null;
		}));
		identity(8L);
		var approval = review(submitted, "APPROVE");
		approval.setWorksiteCodes(List.of("WS1"));
		assertThrows(RuntimeException.class, () -> service.command(approval));
		assertEquals("UN_RECEIVE", mapper.arrange(1L, "F1", Long.valueOf(originalId())).getStatus());
	}

	@Test
	void requiredReceiptWriteFailureRollsBackReleasedToolingCloseAndNewDispatch() {
		db.update("INSERT INTO mes_tooling_execution(id,arrange_code,tooling_code,status,tenant_id,del_flag) VALUES(100,?,'TOOL','CHECKED_IN',1,'0')", mapper.arrange(1L, "F1", Long.valueOf(originalId())).getCode());
		var submitted = submit();
		identity(8L);
		doReturn(0).when(mapper).insertOperation(any());
		assertThrows(RuntimeException.class, () -> service.command(review(submitted, "APPROVE")));
		assertEquals("CHECKED_IN", db.queryForObject("SELECT status FROM mes_tooling_execution WHERE id=100", String.class));
		assertEquals("UN_RECEIVE", mapper.arrange(1L, "F1", Long.valueOf(originalId())).getStatus());
		assertEquals("SUBMITTED", mapper.request(1L, "F1", Long.valueOf(submitted.getResult().getCorrection().getId())).getStatus());
		assertEquals(2, db.queryForObject("SELECT count(*) FROM mes_task_arrange", Integer.class));
	}

	@Test
	void plannedResourceDynamicPolicyReportsAndForeignCandidatesCannotBypassPreview() {
		db.update("UPDATE mes_produce_task SET planned_worksite_code='WS1' WHERE code='P1'");
		assertFalse(service.preview(originalId(), "S1", "WS2").isEligible());
		db.update("UPDATE mes_produce_task SET planned_worksite_code=null WHERE code='P1'");
		db.update("UPDATE mes_task_arrange SET execution_policy_snapshot=? WHERE id=?", "{\"identifierNodePolicy\":{\"action\":\"USE\"}}", Long.valueOf(originalId()));
		assertFalse(service.preview(originalId(), "S1", "WS2").isEligible());
		db.update("UPDATE mes_task_arrange SET execution_policy_snapshot=? WHERE id=?", frozen, Long.valueOf(originalId()));
		assertFalse(service.preview(originalId(), "MISSING", "WS2").isEligible());
		assertFalse(service.preview(originalId(), "S1", "OTHER").isEligible());
		db.update("INSERT INTO mes_task_report(id,arrange_code,tenant_id,del_flag) VALUES(991,?,1,'0')", mapper.arrange(1L, "F1", Long.valueOf(originalId())).getCode());
		assertFalse(service.preview(originalId(), "S1", "WS2").isEligible());
	}

	@Test
	void frozenTemplateSelectionCannotBeReplacedWhileApplicationIsPending() {
		var submitted = submit();
		identity(8L);
		db.update("INSERT INTO mes_execution_template(id,tenant_id,factory_code,status,version_no,priority,del_flag) VALUES(100,1,'F1','PUBLISHED',2,1,'0')");
		assertThrows(RuntimeException.class, () -> service.command(review(submitted, "APPROVE")));
		assertEquals(2, db.queryForObject("SELECT count(*) FROM mes_task_arrange", Integer.class));
	}

	@Test
	void originalBodyCannotBeChangedAndRejectionDoesNotRedispatch() {
		var preview = service.preview(originalId(), "S1", "WS2");
		var command = submitCommand(preview);
		var submitted = service.command(command);
		command.setReason("changed");
		assertThrows(RuntimeException.class, () -> service.command(command));
		identity(8L);
		var rejected = service.command(review(submitted, "REJECT"));
		assertEquals("REJECTED", rejected.getResult().getCorrection().getStatus());
		assertNull(rejected.getResult().getCorrection().getReplacementArrangeId());
		assertEquals(2, db.queryForObject("SELECT count(*) FROM mes_task_arrange", Integer.class));
		identity(7L);
		assertEquals("SUBMITTED", service.receipt(command.getRequestId()).getResult().getCorrection().getStatus());
	}

	@Test
	void originalApplicantCanWithdrawAndExactPageHasStableScopeAndCount() {
		var submitted = submit();
		var withdraw = new MesDispatchCorrectionDTO.Command();
		withdraw.setRequestId(UUID.randomUUID().toString());
		withdraw.setAction("WITHDRAW");
		withdraw.setCorrectionId(submitted.getResult().getCorrection().getId());
		withdraw.setReason("撤回原目标");
		identity(8L);
		assertThrows(RuntimeException.class, () -> service.command(withdraw));
		identity(7L);
		assertEquals("WITHDRAWN", service.command(withdraw).getResult().getCorrection().getStatus());
		assertEquals(1, service.page(1, 20, "WITHDRAWN", originalId()).getTotal());
		assertEquals(0, service.page(1, 20, "SUBMITTED", originalId()).getTotal());
		assertEquals("1", service.page(1, 20, null, null).getTenantId());
		assertEquals("F1", service.page(1, 20, null, null).getFactoryCode());
		assertThrows(RuntimeException.class, () -> service.detail("999999"));
	}

	@Test
	void twoConcurrentApproversCreateExactlyOneReplacement() throws Exception {
		var submitted = submit();
		var first = review(submitted, "APPROVE");
		var second = review(submitted, "APPROVE");
		var barrier = new java.util.concurrent.CyclicBarrier(2);
		var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
		try {
			var a = pool.submit(() -> concurrentApprove(first, 8L, barrier));
			var b = pool.submit(() -> concurrentApprove(second, 9L, barrier));
			assertEquals(1, (a.get(10, java.util.concurrent.TimeUnit.SECONDS) ? 1 : 0) + (b.get(10, java.util.concurrent.TimeUnit.SECONDS) ? 1 : 0));
			assertEquals(3, db.queryForObject("SELECT count(*) FROM mes_task_arrange", Integer.class));
			assertEquals(1, db.queryForObject("SELECT count(*) FROM mes_dispatch_correction_operation WHERE action='APPROVE'", Integer.class));
		} finally {
			pool.shutdownNow();
		}
	}

	private boolean concurrentApprove(MesDispatchCorrectionDTO.Command command, Long actor, java.util.concurrent.CyclicBarrier barrier) throws Exception {
		identity(actor);
		barrier.await(5, java.util.concurrent.TimeUnit.SECONDS);
		try {
			service.command(command);
			return true;
		} catch (RuntimeException expected) {
			return false;
		} finally {
			cleanup();
		}
	}

	private MesDispatchCorrectionDTO.Resolution submit() {
		return service.command(submitCommand(service.preview(originalId(), "S1", "WS2")));
	}

	private MesDispatchCorrectionDTO.Command submitCommand(MesDispatchCorrectionDTO.Preview preview) {
		var c = new MesDispatchCorrectionDTO.Command();
		c.setRequestId(UUID.randomUUID().toString());
		c.setAction("SUBMIT");
		c.setArrangeId(originalId());
		c.setShiftCode("S1");
		c.setWorksiteCodes(List.of("WS2"));
		c.setExpectedFingerprint(preview.getEvidenceFingerprint());
		c.setReason("调整授权工位");
		return c;
	}

	private MesDispatchCorrectionDTO.Command review(MesDispatchCorrectionDTO.Resolution submitted, String action) {
		var c = new MesDispatchCorrectionDTO.Command();
		c.setRequestId(UUID.randomUUID().toString());
		c.setAction(action);
		c.setCorrectionId(submitted.getResult().getCorrection().getId());
		c.setExpectedFingerprint(submitted.getResult().getCorrection().getEvidenceFingerprint());
		c.setReason("已核对具体目标");
		return c;
	}

	private String originalId() {
		return db.queryForObject("SELECT min(id) FROM mes_task_arrange WHERE produce_task_code='P1'", Long.class).toString();
	}

	private void identity(Long id) {
		TenantContextHolder.setTenantId(1L);
		var u = mock(MomUser.class);
		when(u.getId()).thenReturn(id);
		when(u.getUsername()).thenReturn("operator-" + id);
		when(u.getName()).thenReturn("人员" + id);
		SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(u, "", List.of()));
	}

	private void set(Object target, String field, Object value) {
		ReflectionTestUtils.setField(target, field, value);
	}

	@SuppressWarnings("unchecked")
	private <T> T proxy(T target) {
		var p = new ProxyFactory(target);
		p.setProxyTargetClass(true);
		p.addAdvice(new TransactionInterceptor(tx, new AnnotationTransactionAttributeSource()));
		return (T) p.getProxy();
	}

	private String column(String value) {
		return value.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
	}

	private String sqlType(Class<?> type) {
		if (type == Long.class || type == long.class) {
			return "bigint";
		}
		if (type == Integer.class || type == int.class) {
			return "integer";
		}
		if (type == Boolean.class || type == boolean.class) {
			return "boolean";
		}
		if (type == BigDecimal.class) {
			return "decimal(30,8)";
		}
		if (type == LocalDateTime.class || type == java.util.Date.class) {
			return "timestamp";
		}
		return "varchar";
	}

	private void createEntityTable(Class<?> type) {
		var info = TableInfoHelper.getTableInfo(type);
		var fields = new ArrayList<String>();
		fields.add(info.getKeyColumn() + " bigint primary key");
		for (var f : info.getFieldList()) {
			fields.add(f.getColumn() + " " + sqlType(f.getPropertyType()) + (f.getColumn().equals("del_flag") ? " default '0'" : ""));
		}
		db.execute("CREATE TABLE " + info.getTableName() + "(" + String.join(",", fields) + ")");
	}

	private void createLedgerTable(String name, Class<?> type) {
		var fields = new ArrayList<String>();
		for (var f : type.getDeclaredFields()) {
			if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
				fields.add(column(f.getName()) + " " + sqlType(f.getType()) + (f.getName().equals("id") ? " primary key" : ""));
			}
		}
		if (name.equals("mes_execution_template")) {
			fields.add("id bigint");
			fields.add("tenant_id bigint");
			fields.add("del_flag varchar default '0'");
		}
		db.execute("CREATE TABLE " + name + "(" + String.join(",", fields) + ")");
	}
}
