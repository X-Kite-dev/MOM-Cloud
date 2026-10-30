package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.mom.mes.dto.MesWorkpieceTimeDTO;
import com.kitex.mom.mes.dto.MesQualityGateDecision;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.entity.MesProduceTaskEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.mapper.MesTaskArrangeMapper;
import com.kitex.mom.mes.mapper.MesWorkpieceTimeMapper;
import com.kitex.mom.mes.mapper.MesExecutionCommandCancellationMapper;
import com.kitex.mom.mes.service.MesProduceTaskService;
import com.kitex.mom.mes.service.MesTaskService;
import com.kitex.mom.mes.service.MesQualityGateService;
import com.kitex.mom.mes.service.MesQualityQuantityService;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
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
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

/** Real MyBatis SQL and Spring transactions; PG installation/advisory lock use an H2 row-lock equivalent. */
class MesWorkpieceTimeServiceTest {

	private JdbcTemplate db;
	private TransactionTemplate tx;
	private MesWorkpieceTimeService service;
	private final MesTaskArrangeEntity arrange = new MesTaskArrangeEntity();
	private boolean rejectOperationWrite;
	private MesTerminalExecutionGuard terminal;

	@BeforeEach
	void setup() {
		actor("worker-1", "F1");
		var source = new DriverManagerDataSource("jdbc:h2:mem:workpiece-" + UUID.randomUUID()
				+ ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000", "sa", "");
		source.setDriverClassName("org.h2.Driver");
		db = new JdbcTemplate(source);
		db.execute("CREATE SCHEMA mom_mes");
		db.execute("CREATE TABLE mutex(id int primary key)");
		db.update("INSERT INTO mutex VALUES(1)");
		String scope = "id bigint primary key,tenant_id bigint,factory_code varchar,arrange_id bigint,"
				+ "arrange_code varchar,task_code varchar,produce_task_code varchar,identifier varchar,identifier_type varchar,"
				+ "worker_code varchar,device_code varchar,del_flag varchar default '0',";
		db.execute("CREATE TABLE mom_mes.mes_workpiece_time_session(" + scope
				+ "status varchar,pause_source varchar,version_no bigint,capture_basis varchar,"
				+ "unique(tenant_id,factory_code,arrange_id,identifier_type,identifier))");
		db.execute("CREATE TABLE mom_mes.mes_workpiece_time_interval(" + scope
				+ "session_id bigint,kind varchar,start_time timestamp(6),end_time timestamp(6),status varchar,reason_code varchar)");
		db.execute("CREATE TABLE mom_mes.mes_workpiece_time_operation(id bigint primary key,tenant_id bigint,"
				+ "factory_code varchar,request_id varchar,request_hash varchar,session_id bigint,arrange_id bigint,actor_id bigint,"
				+ "action varchar,receipt_json clob,del_flag varchar default '0',unique(tenant_id,factory_code,request_id))");
		db.execute("CREATE TABLE mom_mes.mes_task_report(tenant_id bigint,factory_code varchar,transfer_code varchar,task_code varchar,del_flag varchar)");
		db.execute("CREATE TABLE mom_mes.mes_execution_command_cancellation(id bigint primary key,tenant_id bigint,"
				+ "factory_code varchar,command_kind varchar,request_id varchar,request_hash varchar,actor_id bigint,"
				+ "create_by varchar,create_time timestamp,del_flag varchar,unique(tenant_id,factory_code,command_kind,request_id))");
		var configuration = new Configuration();
		configuration.setMapUnderscoreToCamelCase(true);
		configuration.setEnvironment(new Environment("workpiece", new SpringManagedTransactionFactory(), source));
		configuration.addMapper(MesWorkpieceTimeMapper.class);
		configuration.addMapper(MesExecutionCommandCancellationMapper.class);
		var sql = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration));
		var realMapper = sql.getMapper(MesWorkpieceTimeMapper.class);
		var rows = (MesWorkpieceTimeMapper) Proxy.newProxyInstance(getClass().getClassLoader(),
				new Class<?>[]{MesWorkpieceTimeMapper.class}, (proxy, method, arguments) -> {
					if (method.getName().equals("ready")) {
						return true;
					}
					if (method.getName().equals("lockRequest")) {
						db.queryForObject("SELECT id FROM mutex WHERE id=1 FOR UPDATE", Integer.class);
						return null;
					}
					if (method.getName().equals("insertOperation") && rejectOperationWrite) {
						return 0;
					}
					try {
						return method.invoke(realMapper, arguments);
					} catch (InvocationTargetException failure) {
						throw failure.getCause();
					}
				});
		arrange.setId(11L);
		arrange.setTenantId(1L);
		arrange.setCode("A1").setTaskCode("T1").setProduceTaskCode("P1").setFactoryCode("F1")
				.setWorkerCode("worker-1,worker-2").setStatus("PRODUCT").setBatchNo("BATCH-1")
				.setPlannedDeviceCode("DEVICE-1");
		var arranges = mock(MesTaskArrangeMapper.class);
		when(arranges.selectOne(any())).thenReturn(arrange);
		var locks = mock(MesExecutionTaskLock.class);
		when(locks.arrange(11L)).thenReturn(arrange);
		terminal = mock(MesTerminalExecutionGuard.class);
		var cancellations = new MesExecutionCommandCancellationService(MesFactoryAccessTestSupport.authorized(),
				sql.getMapper(MesExecutionCommandCancellationMapper.class));
		var target = new MesWorkpieceTimeService(rows, arranges, MesFactoryAccessTestSupport.authorized(), locks, terminal, cancellations);
		var manager = new DataSourceTransactionManager(source);
		tx = new TransactionTemplate(manager);
		var proxy = new ProxyFactory(target);
		proxy.setProxyTargetClass(true);
		proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
		service = (MesWorkpieceTimeService) proxy.getProxy();
	}

	@AfterEach
	void clear() {
		SecurityContextHolder.clearContext();
		TenantContextHolder.clear();
		FactoryContextHolder.clear();
	}

	@Test
	void recordsActualRunPauseResumeAndFinishWithoutCreatingProductionQuantity() {
		var started = service.execute(command("START", "SN-1", null));
		assertNotNull(started);
		assertEquals("OPERATOR_OBSERVED", started.captureBasis());
		var paused = service.execute(command("PAUSE", "SN-1", 1L).setReasonCode("MATERIAL"));
		var resumed = service.execute(command("RESUME", "SN-1", paused.versionNo()));
		var finished = service.execute(command("FINISH", "SN-1", resumed.versionNo()));

		assertEquals("FINISHED", finished.status());
		assertEquals(List.of("RUN", "PAUSE", "RUN"), db.queryForList(
				"SELECT kind FROM mom_mes.mes_workpiece_time_interval ORDER BY id", String.class));
		assertEquals(0, count("mes_workpiece_time_interval", "status='OPEN'"));
		assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM mom_mes.mes_task_report", Integer.class));
		assertEquals("PRODUCT", arrange.getStatus());
	}

	@Test
	void identicalRequestReturnsOriginalReceiptAfterDispatchStateChanges() {
		var command = command("START", "SN-1", null);
		var original = service.execute(command);
		arrange.setStatus("FINISH");

		assertEquals(original, service.execute(command));
		assertEquals(original, service.receipt(command.getRequestId()).receipt());
		assertEquals(1, count("mes_workpiece_time_session", "1=1"));
		assertEquals(1, count("mes_workpiece_time_interval", "1=1"));
	}

	@Test
	void originalRequestLookupReportsCurrentAbsenceAfterRollback() {
		rejectOperationWrite = true;
		var command = command("START", "SN-1", null);
		assertThrows(CheckedException.class, () -> service.execute(command));

		assertEquals("ABSENT", service.receipt(command.getRequestId()).state());
		assertEquals(0, count("mes_workpiece_time_interval", "1=1"));
	}

	@Test
	void sameRequestCannotBeReusedForDifferentIdentifier() {
		var command = command("START", "SN-1", null);
		service.execute(command);
		command.setIdentifier("SN-2");

		assertThrows(CheckedException.class, () -> service.execute(command));
		assertEquals(1, count("mes_workpiece_time_session", "1=1"));
	}

	@Test
	void requiredOperationWriteFailureRollsBackSessionAndInterval() {
		rejectOperationWrite = true;

		assertThrows(CheckedException.class, () -> service.execute(command("START", "SN-1", null)));
		assertEquals(0, count("mes_workpiece_time_session", "1=1"));
		assertEquals(0, count("mes_workpiece_time_interval", "1=1"));
	}

	@Test
	void staleVersionCannotCloseCurrentRunningInterval() {
		service.execute(command("START", "SN-1", null));

		assertThrows(CheckedException.class, () -> service.execute(command("PAUSE", "SN-1", 0L).setReasonCode("OTHER")));
		assertEquals(1, count("mes_workpiece_time_interval", "status='OPEN' AND kind='RUN'"));
	}

	@Test
	void dispatchPauseResumeDoesNotResumeAnOperatorsOwnPause() {
		var first = service.execute(command("START", "SN-1", null));
		service.execute(command("START", "SN-2", null));
		service.execute(command("PAUSE", "SN-2", 1L).setReasonCode("OTHER"));
		LocalDateTime pausedAt = first.capturedAt().plusSeconds(1);

		tx.executeWithoutResult(status -> service.pauseForArrange(arrange, pausedAt, "ANDON_PAUSE"));
		tx.executeWithoutResult(status -> service.resumeForArrange(arrange, pausedAt.plusSeconds(5)));

		assertEquals(1, count("mes_workpiece_time_session", "status='RUNNING' AND identifier='SN-1'"));
		assertEquals(1, count("mes_workpiece_time_session", "status='PAUSED' AND pause_source='OPERATOR' AND identifier='SN-2'"));
		assertEquals(1, count("mes_workpiece_time_interval", "kind='PAUSE' AND status='CLOSED' AND reason_code='ANDON_PAUSE'"));
		tx.executeWithoutResult(status -> service.finishForArrange(arrange, pausedAt.plusSeconds(10), "FINISH"));
		assertEquals(0, count("mes_workpiece_time_interval", "status='OPEN'"));
		assertEquals(2, count("mes_workpiece_time_session", "status='FINISHED'"));
	}

	@Test
	void outerDispatchRollbackRestoresWorkpieceRunningFacts() {
		var started = service.execute(command("START", "SN-1", null));

		assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(status -> {
			service.pauseForArrange(arrange, started.capturedAt().plusSeconds(1), "USER_PAUSE");
			throw new IllegalStateException("派工状态写入失败");
		}));
		assertEquals(1, count("mes_workpiece_time_session", "status='RUNNING' AND version_no=1"));
		assertEquals(1, count("mes_workpiece_time_interval", "status='OPEN' AND kind='RUN'"));
	}

	@Test
	void rejectsUnknownTaskOwnershipAndBatchMismatch() {
		db.update("INSERT INTO mom_mes.mes_task_report VALUES(1,'F1','SN-OTHER','T-OTHER','0')");

		assertThrows(CheckedException.class, () -> service.execute(command("START", "SN-OTHER", null)));
		assertThrows(CheckedException.class, () -> service.execute(command("START", "BATCH-WRONG", null).setIdentifierType("BATCH")));
		assertEquals(0, count("mes_workpiece_time_session", "1=1"));
	}

	@Test
	void requiresProductStatusCurrentAssignedWorkerAndActualFactory() {
		arrange.setStatus("RECEIVE");
		assertThrows(CheckedException.class, () -> service.execute(command("START", "SN-1", null)));
		arrange.setStatus("PRODUCT");
		actor("outsider", "F1");
		assertThrows(CheckedException.class, () -> service.execute(command("START", "SN-1", null)));
		actor("worker-1", "F2");
		assertThrows(CheckedException.class, () -> service.execute(command("START", "SN-1", null)));
	}

	@Test
	void concurrentSameRequestProducesOneSessionAndOnePermanentReceipt() throws Exception {
		var command = command("START", "SN-1", null);
		var receipts = concurrent(() -> service.execute(command));

		assertNotNull(receipts.get(0));
		assertEquals(receipts.get(0), receipts.get(1));
		assertEquals(1, count("mes_workpiece_time_session", "1=1"));
		assertEquals(1, count("mes_workpiece_time_operation", "action='START'"));
	}

	@Test
	void pendingTerminalCommandCannotStartOrResumeTimer() {
		service.execute(command("START", "SN-1", null));
		service.execute(command("PAUSE", "SN-1", 1L).setReasonCode("OTHER"));
		doThrow(new CheckedException("存在未决终端命令")).when(terminal).assertUnoccupied(any());

		assertThrows(CheckedException.class, () -> service.execute(command("RESUME", "SN-1", 2L)));
		assertThrows(CheckedException.class, () -> service.execute(command("START", "SN-2", null)));
		assertEquals(1, count("mes_workpiece_time_session", "status='PAUSED'"));
		assertEquals(1, count("mes_workpiece_time_session", "1=1"));
	}

	@Test
	void knownOtherArrangementCannotTimeSameSinglePieceConcurrently() {
		service.execute(command("START", "SN-1", null));
		db.update("UPDATE mom_mes.mes_workpiece_time_session SET arrange_id=12,arrange_code='A2'");

		assertThrows(CheckedException.class, () -> service.execute(command("START", "SN-1", null)));
		assertEquals(1, count("mes_workpiece_time_session", "1=1"));
	}

	@Test
	void actualDispatchPauseClosesWorkpieceAtTheSameProductionEvent() throws Exception {
		service.execute(command("START", "SN-1", null));
		var dispatch = dispatch();
		dispatch.arrange.setStatus("PRODUCT");

		tx.executeWithoutResult(status -> dispatch.start.pause(11L));

		assertEquals(1, count("mes_workpiece_time_session", "status='PAUSED' AND pause_source='DISPATCH'"));
		assertEquals(1, count("mes_workpiece_time_interval", "kind='RUN' AND status='CLOSED'"));
		assertEquals(1, count("mes_workpiece_time_interval", "kind='PAUSE' AND status='OPEN' AND reason_code='MANUAL_PAUSE'"));
	}

	@Test
	void actualDispatchResumeOpensWorkpieceAtItsProductionSegmentTime() throws Exception {
		var start = service.execute(command("START", "SN-1", null));
		tx.executeWithoutResult(status -> service.pauseForArrange(arrange, start.capturedAt(), "MANUAL_PAUSE"));
		var dispatch = dispatch();
		dispatch.arrange.setStatus("PAUSE");

		tx.executeWithoutResult(status -> dispatch.start.start(11L));

		assertEquals("PRODUCT", dispatch.arrange.getStatus());
		assertEquals(dispatch.arrange.getStartTime(), db.queryForObject("SELECT start_time FROM "
				+ "mom_mes.mes_workpiece_time_interval WHERE status='OPEN' AND kind='RUN'", LocalDateTime.class));
	}

	@Test
	void actualDispatchPauseFailureRollsBackItsWorkpieceHook() throws Exception {
		service.execute(command("START", "SN-1", null));
		var dispatch = dispatch();
		dispatch.arrange.setStatus("PRODUCT");
		when(dispatch.dependency(MesTaskArrangeMapper.class).updateById(any(MesTaskArrangeEntity.class))).thenReturn(0);

		assertThrows(CheckedException.class, () -> tx.executeWithoutResult(status -> dispatch.start.pause(11L)));
		assertEquals(1, count("mes_workpiece_time_session", "status='RUNNING' AND version_no=1"));
		assertEquals(1, count("mes_workpiece_time_interval", "kind='RUN' AND status='OPEN'"));
		assertEquals(0, count("mes_workpiece_time_operation", "action='DISPATCH_PAUSE'"));
	}

	@Test
	void actualDispatchFinishClosesWorkpieceAtTheExactFinishTime() throws Exception {
		TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "workpiece-finish"),
				MesProduceTaskEntity.class);
		service.execute(command("START", "SN-1", null));
		var dispatch = dispatch();
		dispatch.arrange.setStatus("PRODUCT").setStartTime(LocalDateTime.now().minusSeconds(1));
		var task = new MesTaskEntity().setCode("T1").setFactoryCode("F1").setStatus("PRODUCT");
		task.setId(31L);
		task.setTenantId(1L);
		when(dispatch.dependency(MesTaskService.class).getOne(any(Wrapper.class))).thenReturn(task);
		when(dispatch.dependency(MesTaskService.class).update(any(Wrapper.class))).thenReturn(true);
		var quality = new MesQualityGateDecision().setAllowed(true);
		when(dispatch.dependency(MesQualityGateService.class).checkFinish(any(), any())).thenReturn(quality);
		when(dispatch.dependency(MesQualityQuantityService.class).reconcile(any(), any())).thenReturn(quality);
		when(dispatch.dependency(MesTaskArrangeMapper.class).update(any(), any())).thenReturn(1);
		when(dispatch.dependency(MesProduceTaskService.class).update(any(Wrapper.class))).thenReturn(true);
		when(dispatch.dependency(MesDispatchCompletionGuard.class).evaluate(any()))
				.thenReturn(new MesDispatchCompletionGuard.Decision("FINISH", java.math.BigDecimal.ZERO));

		tx.executeWithoutResult(status -> dispatch.start.finish(11L));

		assertEquals(0, count("mes_workpiece_time_interval", "status='OPEN'"));
		assertEquals(dispatch.arrange.getFinishTime(), db.queryForObject("SELECT end_time FROM "
				+ "mom_mes.mes_workpiece_time_interval WHERE kind='RUN' AND identifier='SN-1'", LocalDateTime.class));
	}

	@Test
	void actualDispatchCloseTerminatesRunningAndPersonallyPausedSessions() throws Exception {
		service.execute(command("START", "SN-1", null));
		service.execute(command("START", "SN-2", null));
		service.execute(command("PAUSE", "SN-2", 1L).setReasonCode("OTHER"));
		var dispatch = dispatch();
		dispatch.arrange.setStatus("PAUSE");
		when(dispatch.dependency(MesTaskArrangeMapper.class).update(any(), any())).thenReturn(1);

		tx.executeWithoutResult(status -> dispatch.start.close(11L));

		assertEquals(2, count("mes_workpiece_time_session", "status='FINISHED'"));
		assertEquals(0, count("mes_workpiece_time_interval", "status='OPEN'"));
	}

	@Test
	void completedHistoryDoesNotBlockActualDispatchPauseResumeAndClose() throws Exception {
		completedHistory();
		service.execute(command("START", "SN-ACTIVE", null));
		var dispatch = dispatch();
		dispatch.arrange.setStatus("PRODUCT");

		tx.executeWithoutResult(status -> dispatch.start.pause(11L));
		assertEquals(1, count("mes_workpiece_time_session", "status='PAUSED' AND identifier='SN-ACTIVE'"));
		tx.executeWithoutResult(status -> dispatch.start.start(11L));
		assertEquals(1, count("mes_workpiece_time_session", "status='RUNNING' AND identifier='SN-ACTIVE'"));
		dispatch.arrange.setStatus("PAUSE");
		when(dispatch.dependency(MesTaskArrangeMapper.class).update(any(), any())).thenReturn(1);
		tx.executeWithoutResult(status -> dispatch.start.close(11L));

		assertEquals(1002, count("mes_workpiece_time_session", "status='FINISHED'"));
		assertEquals(0, count("mes_workpiece_time_interval", "status='OPEN'"));
	}

	@Test
	void completedHistoryDoesNotBlockActualDispatchFinish() throws Exception {
		completedHistory();
		actualDispatchFinishClosesWorkpieceAtTheExactFinishTime();

		assertEquals(1002, count("mes_workpiece_time_session", "status='FINISHED'"));
	}

	@Test
	void overOnePageOfActiveSessionsDoesNotBlockActualDispatchFinish() throws Exception {
		activeSessions();
		actualDispatchFinishClosesWorkpieceAtTheExactFinishTime();

		assertEquals(1002, count("mes_workpiece_time_session", "status='FINISHED'"));
		assertEquals(0, count("mes_workpiece_time_interval", "status='OPEN'"));
	}

	@Test
	void actualDispatchCloseTerminatesEveryPageOfActiveSessions() throws Exception {
		activeSessions();
		var dispatch = dispatch();
		dispatch.arrange.setStatus("PAUSE");
		when(dispatch.dependency(MesTaskArrangeMapper.class).update(any(), any())).thenReturn(1);

		tx.executeWithoutResult(status -> dispatch.start.close(11L));

		assertEquals(1001, count("mes_workpiece_time_session", "status='FINISHED'"));
		assertEquals(0, count("mes_workpiece_time_interval", "status='OPEN'"));
	}

	@Test
	void historicalPagesRemainReadableAndCurrentActiveSessionIsOnFirstPage() {
		completedHistory();
		service.execute(command("START", "SN-CURRENT", null));

		var first = service.view(11L);
		assertEquals(100, first.sessions().size());
		assertEquals("SN-CURRENT", first.sessions().get(0).getIdentifier());
		assertEquals(200, first.intervals().size());
		assertEquals(1002, first.sessionTotal());
		assertEquals(1002, first.intervalTotal());
		assertEquals(1, first.sessionPage());
		assertEquals(100, first.sessionPageSize());
		assertEquals(200, first.intervalPageSize());

		var second = service.view(11L, 2, 2);
		assertEquals(2, second.sessionPage());
		assertEquals(2, second.intervalPage());
		assertEquals(100, second.sessions().size());
		assertEquals(200, second.intervals().size());
		assertTrue(second.sessions().stream().noneMatch(row -> first.sessions().stream().anyMatch(previous -> previous.getId().equals(row.getId()))));
		assertTrue(second.intervals().stream().noneMatch(row -> first.intervals().stream().anyMatch(previous -> previous.getId().equals(row.getId()))));

		var last = service.view(11L, 11, 6);
		assertEquals(2, last.sessions().size());
		assertEquals(2, last.intervals().size());
		assertThrows(CheckedException.class, () -> service.view(11L, 0, 1));
	}

	private void activeSessions() {
		completedHistory();
		db.update("UPDATE mom_mes.mes_workpiece_time_session SET status='RUNNING',version_no=1");
		db.update("UPDATE mom_mes.mes_workpiece_time_interval SET status='OPEN',end_time=null");
	}

	@Test
	void permanentCancellationRejectsAnOriginalPostThatArrivesLater() {
		var original = command("START", "SN-DELAYED", null);

		var result = service.cancel(original);
		assertNotNull(result);
		assertEquals("CANCELLED", result.state());
		assertEquals("CANCELLED", service.receipt(original.getRequestId()).state());
		assertThrows(CheckedException.class, () -> service.execute(original));
		assertEquals(0, count("mes_workpiece_time_session", "1=1"));
		assertEquals(1, count("mes_execution_command_cancellation", "command_kind='WORKPIECE_TIME'"));
	}

	@Test
	void rejectedOriginalCanBeCancelledBeforeCorrectedNewRequest() {
		var rejected = command("START", "SN-1", null);
		arrange.setStatus("RECEIVE");
		assertThrows(CheckedException.class, () -> service.execute(rejected));

		var cancelled = service.cancel(rejected);
		assertNotNull(cancelled);
		assertEquals("CANCELLED", cancelled.state());
		arrange.setStatus("PRODUCT");
		var accepted = service.execute(command("START", "SN-1", null));
		assertEquals("RUNNING", accepted.status());
		assertThrows(CheckedException.class, () -> service.execute(rejected));
		assertEquals(1, count("mes_workpiece_time_session", "1=1"));
	}

	@Test
	void cancellingAppliedOriginalReturnsItsOriginalReceiptWithoutChangingProductionFacts() {
		var command = command("START", "SN-1", null);
		var original = service.execute(command);

		var result = service.cancel(command);
		assertNotNull(result);
		assertEquals("APPLIED", result.state());
		assertEquals(original, result.receipt());
		assertEquals(0, count("mes_execution_command_cancellation", "1=1"));
		assertEquals(1, count("mes_workpiece_time_interval", "status='OPEN' AND kind='RUN'"));
	}

	@Test
	void cancellationReplayKeepsOriginalHashAndRejectsChangedContent() {
		var command = command("START", "SN-1", null);
		var first = service.cancel(command);
		assertNotNull(first);

		assertEquals(first, service.cancel(command));
		command.setIdentifier("SN-2");
		assertThrows(CheckedException.class, () -> service.cancel(command));
		assertEquals(1, count("mes_execution_command_cancellation", "1=1"));
	}

	private void completedHistory() {
		db.update("INSERT INTO mom_mes.mes_workpiece_time_session(id,tenant_id,factory_code,arrange_id,arrange_code,"
				+ "task_code,produce_task_code,identifier,identifier_type,worker_code,device_code,status,version_no,capture_basis) "
				+ "SELECT X,1,'F1',11,'A1','T1','P1',CONCAT('HISTORY-',X),'SN','worker-1','DEVICE-1','FINISHED',2,"
				+ "'OPERATOR_OBSERVED' FROM SYSTEM_RANGE(1,1001)");
		db.update("INSERT INTO mom_mes.mes_workpiece_time_interval(id,session_id,tenant_id,factory_code,arrange_id,arrange_code,"
				+ "task_code,produce_task_code,identifier,identifier_type,worker_code,device_code,kind,start_time,end_time,status) "
				+ "SELECT X,X,1,'F1',11,'A1','T1','P1',CONCAT('HISTORY-',X),'SN','worker-1','DEVICE-1','RUN',"
				+ "TIMESTAMP '2026-10-01 00:00:00',TIMESTAMP '2026-10-01 00:00:01','CLOSED' FROM SYSTEM_RANGE(1,1001)");
	}

	@SuppressWarnings("unchecked")
	private MesStartContractGuardTest.Fixture dispatch() throws Exception {
		var fixture = new MesStartContractGuardTest.Fixture(false, false);
		ReflectionTestUtils.setField(fixture.start, "workpieceTimeService", service);
		var process = new MesProduceTaskEntity().setCode("P1").setTaskCode("T1").setFactoryCode("F1")
				.setRouteCode("R1").setStatus("PRODUCT");
		process.setId(21L);
		process.setTenantId(1L);
		when(fixture.dependency(MesProduceTaskService.class).getOne(any(Wrapper.class), anyBoolean())).thenReturn(process);
		when(fixture.dependency(MesProduceTaskService.class).getOne(any(Wrapper.class))).thenReturn(process);
		when(fixture.dependency(MesProduceTaskService.class).getById(21L)).thenReturn(process);
		var task = new MesTaskEntity().setCode("T1").setFactoryCode("F1").setStatus("PRODUCT");
		task.setId(31L);
		task.setTenantId(1L);
		when(fixture.dependency(MesTaskService.class).getOne(any(Wrapper.class))).thenReturn(task);
		when(fixture.dependency(MesDispatchCompletionGuard.class).evaluate(any()))
				.thenReturn(new MesDispatchCompletionGuard.Decision(null, null));
		return fixture;
	}

	private List<MesWorkpieceTimeDTO.Receipt> concurrent(Callable<MesWorkpieceTimeDTO.Receipt> action) throws Exception {
		var pool = Executors.newFixedThreadPool(2);
		try {
			Callable<MesWorkpieceTimeDTO.Receipt> authenticated = () -> {
				actor("worker-1", "F1");
				try {
					return action.call();
				} finally {
					clear();
				}
			};
			var first = pool.submit(authenticated);
			var second = pool.submit(authenticated);
			return java.util.Arrays.asList(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
		} finally {
			pool.shutdownNow();
		}
	}

	private int count(String table, String predicate) {
		return db.queryForObject("SELECT COUNT(*) FROM mom_mes." + table + " WHERE " + predicate, Integer.class);
	}

	private MesWorkpieceTimeDTO.Command command(String action, String identifier, Long version) {
		return new MesWorkpieceTimeDTO.Command().setRequestId(UUID.randomUUID().toString()).setArrangeId("11")
				.setAction(action).setIdentifierType("SN").setIdentifier(identifier).setExpectedVersion(version);
	}

	private void actor(String worker, String factory) {
		TenantContextHolder.setTenantId(1L);
		FactoryContextHolder.setFactoryCode(factory);
		var actor = mock(MomUser.class);
		when(actor.getId()).thenReturn("worker-1".equals(worker) ? 7L : 8L);
		when(actor.getTenantId()).thenReturn(1L);
		when(actor.getUsername()).thenReturn(worker);
		SecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken(actor, "N/A", List.of()));
	}
}
