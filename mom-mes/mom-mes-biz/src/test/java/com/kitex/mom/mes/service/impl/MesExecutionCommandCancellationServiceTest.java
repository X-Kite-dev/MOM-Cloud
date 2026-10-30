package com.kitex.mom.mes.service.impl;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.mom.mes.mapper.MesExecutionCommandCancellationMapper;
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
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.InvocationTargetException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Actual annotated MyBatis SQL, default SESSION cache and Spring transactions; PG immutability triggers are checked separately. */
class MesExecutionCommandCancellationServiceTest {
	private static final String KIND = "WORKPIECE_TIME";
	private static final String REQUEST = "original.command:0001";
	private static final String HASH = "a".repeat(64);
	private JdbcTemplate db;
	private TransactionTemplate tx;
	private TransactionTemplate other;
	private MesExecutionCommandCancellationService service;

	@BeforeEach
	void setup() {
		actor(7L, "F1", 1L);
		var source = new DriverManagerDataSource("jdbc:h2:mem:cancellation-" + UUID.randomUUID()
				+ ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000", "sa", "");
		source.setDriverClassName("org.h2.Driver");
		db = new JdbcTemplate(source);
		db.execute("CREATE SCHEMA mom_mes");
		db.execute("CREATE TABLE mom_mes.mes_execution_command_cancellation(id bigint primary key,tenant_id bigint not null,"
				+ "factory_code varchar(100) not null,command_kind varchar(40) not null,request_id varchar(100) not null,"
				+ "request_hash varchar(64) not null,actor_id bigint not null,create_by varchar(64),create_time timestamp(6) not null,"
				+ "del_flag char(1) default '0' not null,unique(tenant_id,factory_code,command_kind,request_id))");
		db.execute("CREATE TABLE domain_fact(id bigint primary key)");
		var configuration = new Configuration();
		configuration.setMapUnderscoreToCamelCase(true);
		configuration.setEnvironment(new Environment("cancellation", new SpringManagedTransactionFactory(), source));
		configuration.addMapper(MesExecutionCommandCancellationMapper.class);
		var sql = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration));
		var mapper = sql.getMapper(MesExecutionCommandCancellationMapper.class);
		var manager = new DataSourceTransactionManager(source);
		tx = new TransactionTemplate(manager);
		other = new TransactionTemplate(manager);
		// Only test evidence written by another committed transaction; the production helper never starts this boundary.
		other.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		var proxy = new ProxyFactory(new MesExecutionCommandCancellationService(MesFactoryAccessTestSupport.authorized(), mapper));
		proxy.setProxyTargetClass(true);
		var transactions = new TransactionInterceptor();
		transactions.setTransactionManager(manager);
		transactions.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
		proxy.addAdvice(transactions);
		service = (MesExecutionCommandCancellationService) proxy.getProxy();
	}

	@AfterEach
	void clear() {
		SecurityContextHolder.clearContext();
		TenantContextHolder.clear();
		FactoryContextHolder.clear();
	}

	@Test
	void startBindingCancellationPermanentlyRejectsDelayedExecution() {
		tx.executeWithoutResult(status -> service.cancel("START_MATERIAL_BINDING", REQUEST, HASH));

		assertTrue(service.cancelled("START_MATERIAL_BINDING", REQUEST));
		assertThrows(CheckedException.class, () -> tx.executeWithoutResult(status ->
				service.assertActive("START_MATERIAL_BINDING", REQUEST, HASH)));
	}

	@Test
	void cancelPersistsServerIdentityExactHashAndMicrosecondTime() {
		LocalDateTime before = LocalDateTime.now();

		tx.executeWithoutResult(status -> service.cancel(KIND, REQUEST, HASH));

		var row = db.queryForMap("SELECT * FROM mom_mes.mes_execution_command_cancellation");
		assertTrue(((Number) row.get("ID")).longValue() > 0);
		assertEquals(1L, ((Number) row.get("TENANT_ID")).longValue());
		assertEquals("F1", row.get("FACTORY_CODE"));
		assertEquals(7L, ((Number) row.get("ACTOR_ID")).longValue());
		assertEquals("worker-7", row.get("CREATE_BY"));
		assertEquals(KIND, row.get("COMMAND_KIND"));
		assertEquals(REQUEST, row.get("REQUEST_ID"));
		assertEquals(HASH, row.get("REQUEST_HASH"));
		assertEquals("0", row.get("DEL_FLAG"));
		var time = db.queryForObject("SELECT create_time FROM mom_mes.mes_execution_command_cancellation", LocalDateTime.class);
		assertFalse(time.isBefore(before.minusSeconds(1)));
		assertFalse(time.isAfter(LocalDateTime.now()));
		assertEquals(0, time.getNano() % 1000);
	}

	@Test
	void identicalCancellationReplaysTheOriginalImmutableRow() {
		tx.executeWithoutResult(status -> service.cancel(KIND, REQUEST, HASH));
		var original = db.queryForMap("SELECT * FROM mom_mes.mes_execution_command_cancellation");

		tx.executeWithoutResult(status -> service.cancel(KIND, REQUEST, HASH));

		assertEquals(original, db.queryForMap("SELECT * FROM mom_mes.mes_execution_command_cancellation"));
		assertEquals(1, count());
	}

	@Test
	void delayedOriginalPostCannotBecomeActiveAfterCancellation() {
		tx.executeWithoutResult(status -> service.cancel(KIND, REQUEST, HASH));

		assertThrows(CheckedException.class, () -> tx.executeWithoutResult(status -> service.assertActive(KIND, REQUEST, HASH)));
		assertEquals(1, count());
	}

	@Test
	void anyTombstoneRejectsTheOriginalNumberEvenFromAnotherActorOrHash() {
		tx.executeWithoutResult(status -> service.cancel(KIND, REQUEST, HASH));
		actor(8L, "F1", 1L);

		assertThrows(CheckedException.class, () -> tx.executeWithoutResult(status -> service.assertActive(KIND, REQUEST, "b".repeat(64))));
	}

	@Test
	void changedHashCannotReplaceAnOriginalCancellation() {
		tx.executeWithoutResult(status -> service.cancel(KIND, REQUEST, HASH));

		assertThrows(CheckedException.class, () -> tx.executeWithoutResult(status -> service.cancel(KIND, REQUEST, "b".repeat(64))));
		assertEquals(HASH, db.queryForObject("SELECT request_hash FROM mom_mes.mes_execution_command_cancellation", String.class));
	}

	@Test
	void anotherActorCannotTakeOwnershipOfTheCancelledOriginalNumber() {
		tx.executeWithoutResult(status -> service.cancel(KIND, REQUEST, HASH));
		actor(8L, "F1", 1L);

		assertThrows(CheckedException.class, () -> tx.executeWithoutResult(status -> service.cancel(KIND, REQUEST, HASH)));
		assertEquals(7L, db.queryForObject("SELECT actor_id FROM mom_mes.mes_execution_command_cancellation", Long.class));
	}

	@Test
	void receiptReadsOnlyTheCurrentTenantFactoryActorAndCommandKind() {
		tx.executeWithoutResult(status -> service.cancel(KIND, REQUEST, HASH));

		assertTrue(service.cancelled(KIND, REQUEST));
		assertFalse(service.cancelled("PROCESS_COMPOSITION", REQUEST));
		actor(8L, "F1", 1L);
		assertFalse(service.cancelled(KIND, REQUEST));
		actor(7L, "F2", 1L);
		assertFalse(service.cancelled(KIND, REQUEST));
		actor(7L, "F1", 2L);
		assertFalse(service.cancelled(KIND, REQUEST));
	}

	@Test
	void bothCommandKindsCanRetireTheSameNumberIndependently() {
		tx.executeWithoutResult(status -> {
			service.cancel(KIND, REQUEST, HASH);
			service.cancel("PROCESS_COMPOSITION", REQUEST, HASH);
		});

		assertTrue(service.cancelled(KIND, REQUEST));
		assertTrue(service.cancelled("PROCESS_COMPOSITION", REQUEST));
		assertEquals(2, count());
	}

	@Test
	void theSameOriginalNumberCanExistInIndependentAuthenticatedScopes() {
		tx.executeWithoutResult(status -> service.cancel(KIND, REQUEST, HASH));
		actor(7L, "F2", 1L);
		tx.executeWithoutResult(status -> service.cancel(KIND, REQUEST, HASH));
		actor(7L, "F1", 2L);
		tx.executeWithoutResult(status -> service.cancel(KIND, REQUEST, HASH));

		assertEquals(3, count());
		assertTrue(service.cancelled(KIND, REQUEST));
	}

	@Test
	void absentOriginalIsActiveWithoutCreatingAnyCancellation() {
		tx.executeWithoutResult(status -> service.assertActive(KIND, REQUEST, HASH));

		assertEquals(0, count());
		assertFalse(service.cancelled(KIND, REQUEST));
	}

	@Test
	void writeAndActiveCheckRequireTheCallersOriginalTransaction() {
		assertThrows(IllegalTransactionStateException.class, () -> service.cancel(KIND, REQUEST, HASH));
		assertThrows(IllegalTransactionStateException.class, () -> service.assertActive(KIND, REQUEST, HASH));
		assertEquals(0, count());
	}

	@Test
	void outerRollbackRemovesBothTheTombstoneAndTheDomainWrite() {
		assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(status -> {
			db.update("INSERT INTO domain_fact VALUES(1)");
			service.cancel(KIND, REQUEST, HASH);
			throw new IllegalStateException("domain failed after cancellation");
		}));

		assertEquals(0, count());
		assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM domain_fact", Integer.class));
		assertFalse(service.cancelled(KIND, REQUEST));
	}

	@Test
	void failedTombstoneInsertRollsBackTheCallersDomainWrite() {
		db.execute("ALTER TABLE mom_mes.mes_execution_command_cancellation ADD CONSTRAINT reject_positive_id CHECK(id<0)");

		assertThrows(RuntimeException.class, () -> tx.executeWithoutResult(status -> {
			db.update("INSERT INTO domain_fact VALUES(1)");
			service.cancel(KIND, REQUEST, HASH);
		}));

		assertEquals(0, count());
		assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM domain_fact", Integer.class));
	}

	@Test
	void anEarlierEmptySessionReadCannotHideACommittedCancellationFromDelayedPost() {
		tx.executeWithoutResult(status -> {
			service.assertActive(KIND, REQUEST, HASH);
			other.executeWithoutResult(independent -> externalCancellation());

			assertThrows(CheckedException.class, () -> service.assertActive(KIND, REQUEST, HASH));
			status.setRollbackOnly();
		});
		assertEquals(1, count());
	}

	@Test
	void receiptReadRefreshesAnEarlierNegativeSessionCacheEntry() {
		tx.executeWithoutResult(status -> {
			assertFalse(service.cancelled(KIND, REQUEST));
			other.executeWithoutResult(independent -> externalCancellation());

			assertTrue(service.cancelled(KIND, REQUEST));
		});
	}

	@Test
	void missingTableIsAnErrorInsteadOfAnAbsentCancellationReceipt() {
		db.execute("DROP TABLE mom_mes.mes_execution_command_cancellation");

		assertThrows(RuntimeException.class, () -> service.cancelled(KIND, REQUEST));
		assertThrows(RuntimeException.class, () -> tx.executeWithoutResult(status -> service.assertActive(KIND, REQUEST, HASH)));
	}

	@Test
	void invalidCommandIdentityNeverPersistsOrQueriesAnAlternativeScope() {
		for (String kind : new String[]{null, "", "OTHER", "WORKPIECE_TIME "}) {
			assertThrows(CheckedException.class, () -> tx.executeWithoutResult(status -> service.cancel(kind, REQUEST, HASH)));
			assertThrows(CheckedException.class, () -> service.cancelled(kind, REQUEST));
		}
		for (String request : new String[]{null, "", " with spaces ", "x".repeat(101), "bad\nrequest"}) {
			assertThrows(CheckedException.class, () -> tx.executeWithoutResult(status -> service.cancel(KIND, request, HASH)));
		}
		for (String hash : new String[]{null, "", "a".repeat(63), "g".repeat(64)}) {
			assertThrows(CheckedException.class, () -> tx.executeWithoutResult(status -> service.cancel(KIND, REQUEST, hash)));
			assertThrows(CheckedException.class, () -> tx.executeWithoutResult(status -> service.assertActive(KIND, REQUEST, hash)));
		}
		assertEquals(0, count());
	}

	@Test
	void unauthenticatedMissingOrSkippedScopeNeverCreatesATombstone() {
		SecurityContextHolder.clearContext();
		assertThrows(CheckedException.class, () -> tx.executeWithoutResult(status -> service.cancel(KIND, REQUEST, HASH)));
		actor(7L, "F1", 1L);
		TenantContextHolder.setTenantSkip();
		assertThrows(CheckedException.class, () -> tx.executeWithoutResult(status -> service.cancel(KIND, REQUEST, HASH)));
		actor(7L, "", 1L);
		assertThrows(CheckedException.class, () -> service.cancelled(KIND, REQUEST));
		actor(0L, "F1", 1L);
		assertThrows(CheckedException.class, () -> tx.executeWithoutResult(status -> service.cancel(KIND, REQUEST, HASH)));
		assertEquals(0, count());
	}

	private void externalCancellation() {
		db.update("INSERT INTO mom_mes.mes_execution_command_cancellation VALUES(99,1,'F1',?,?,?,7,'worker-7',CURRENT_TIMESTAMP,'0')",
				KIND, REQUEST, HASH);
	}

	private int count() {
		return db.queryForObject("SELECT COUNT(*) FROM mom_mes.mes_execution_command_cancellation", Integer.class);
	}

	private void actor(Long id, String factory, Long tenant) {
		TenantContextHolder.clear();
		FactoryContextHolder.clear();
		TenantContextHolder.setTenantId(tenant);
		FactoryContextHolder.setFactoryCode(factory);
		var user = mock(MomUser.class);
		when(user.getId()).thenReturn(id);
		when(user.getTenantId()).thenReturn(tenant);
		when(user.getUsername()).thenReturn("worker-" + id);
		SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, "N/A", List.of()));
	}

	public static void main(String[] args) throws Exception {
		int passed = 0;
		int failed = 0;
		for (var method : MesExecutionCommandCancellationServiceTest.class.getDeclaredMethods()) {
			if (!method.isAnnotationPresent(Test.class)) {
				continue;
			}
			var test = new MesExecutionCommandCancellationServiceTest();
			try {
				test.setup();
				method.invoke(test);
				System.out.println("PASS " + method.getName());
				passed++;
			} catch (InvocationTargetException error) {
				System.out.println("FAIL " + method.getName() + ": " + error.getCause());
				failed++;
			} finally {
				test.clear();
			}
		}
		System.out.println("Tests: " + (passed + failed) + ", passed: " + passed + ", failed: " + failed);
		if (failed != 0) {
			throw new AssertionError("Command cancellation regressions failed: " + failed);
		}
	}
}
