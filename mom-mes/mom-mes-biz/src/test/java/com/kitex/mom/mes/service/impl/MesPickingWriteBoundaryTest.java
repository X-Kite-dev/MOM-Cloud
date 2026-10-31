package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.mom.common.seq.api.feign.RemoteSeqConfigService;
import com.kitex.mom.mdm.feign.RemoteMdmFactoryAccessService;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.enums.MesPickingBillStateEnum;
import com.kitex.mom.mes.mapper.*;
import com.kitex.mom.wms.api.feign.RemoteProducePickingNoticeService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
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
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Actual picking commands, SQL and Spring rollback; only MDM/WMS and unrelated receipt/rework boundaries are doubles. */
class MesPickingWriteBoundaryTest {

	private JdbcTemplate db;
	private MesWarehousePickingBillServiceImpl service;
	private MesWarehousePickingBillMapper bills;
	private MesWarehousePickingBillDetailMapper details;
	private MesTaskMapper tasks;
	private RemoteMdmFactoryAccessService factories;
	private RemoteProducePickingNoticeService wms;
	private MesMaterialReceiptService receipts;

	@BeforeEach
	void setup() throws Exception {
		TenantContextHolder.setTenantId(1L);
		FactoryContextHolder.setFactoryCode("F1");
		var user = new MomUser(7L, "operator", 1L, null, null, null, "Operator", null,
				1L, "unused", true, true, "USER", true, true, List.of());
		SecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken(user, "unused", List.of()));
		var source = new DriverManagerDataSource("jdbc:h2:mem:picking-write-" + UUID.randomUUID()
				+ ";DB_CLOSE_DELAY=-1", "sa", "");
		source.setDriverClassName("org.h2.Driver");
		db = new JdbcTemplate(source);
		var config = new MybatisConfiguration();
		config.setMapUnderscoreToCamelCase(true);
		var global = new com.baomidou.mybatisplus.core.config.GlobalConfig();
		global.setDbConfig(new com.baomidou.mybatisplus.core.config.GlobalConfig.DbConfig());
		global.setMetaObjectHandler(new com.kitex.common.data.mybatis.MybatisPlusMetaObjectHandler());
		com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.setGlobalConfig(config, global);
		for (Class<?> mapper : List.of(MesWarehousePickingBillMapper.class,
				MesWarehousePickingBillDetailMapper.class, MesTaskMapper.class)) {
			config.addMapper(mapper);
		}
		for (Class<?> entity : List.of(MesWarehousePickingBillEntity.class,
				MesWarehousePickingBillDetailEntity.class, MesTaskEntity.class)) {
			createTable(config, entity);
		}
		var factory = new MybatisSqlSessionFactoryBean();
		factory.setDataSource(source);
		factory.setConfiguration(config);
		factory.setGlobalConfig(global);
		var sessions = new SqlSessionTemplate(factory.getObject());
		bills = mock(MesWarehousePickingBillMapper.class, org.mockito.AdditionalAnswers.delegatesTo(
				sessions.getMapper(MesWarehousePickingBillMapper.class)));
		details = mock(MesWarehousePickingBillDetailMapper.class, org.mockito.AdditionalAnswers.delegatesTo(
				sessions.getMapper(MesWarehousePickingBillDetailMapper.class)));
		tasks = sessions.getMapper(MesTaskMapper.class);
		factories = mock(RemoteMdmFactoryAccessService.class);
		when(factories.check(any())).thenReturn(R.ok(true));
		wms = mock(RemoteProducePickingNoticeService.class);
		when(wms.add(any())).thenReturn(R.ok(true));
		when(wms.voidObjByDocumentId(any(), any())).thenReturn(R.ok(true));
		var sequence = mock(RemoteSeqConfigService.class);
		when(sequence.create(any())).thenReturn(R.ok("PICK-1"));
		Map<Class<?>, Object> dependencies = new HashMap<>();
		dependencies.put(MesWarehousePickingBillDetailMapper.class, details);
		dependencies.put(MesTaskMapper.class, tasks);
		dependencies.put(RemoteSeqConfigService.class, sequence);
		dependencies.put(RemoteProducePickingNoticeService.class, wms);
		dependencies.put(MesActualFactoryAccessService.class, new MesActualFactoryAccessService(factories));
		receipts = mock(MesMaterialReceiptService.class);
		dependencies.put(MesMaterialReceiptService.class, receipts);
		var constructor = MesWarehousePickingBillServiceImpl.class.getConstructors()[0];
		Object[] arguments = Arrays.stream(constructor.getParameterTypes())
				.map(type -> dependencies.computeIfAbsent(type, ignored -> mock(type))).toArray();
		var target = spy((MesWarehousePickingBillServiceImpl) constructor.newInstance(arguments));
		ReflectionTestUtils.setField(target, "baseMapper", bills);
		// Replace MPJ's relation-loader infrastructure, while keeping both persisted reads and all command writes real.
		doAnswer(call -> {
			MesWarehousePickingBillEntity bill = bills.selectById((Long) call.getArgument(0));
			bill.setDetailList(details.selectList(Wrappers.<MesWarehousePickingBillDetailEntity>lambdaQuery()
					.eq(MesWarehousePickingBillDetailEntity::getBillId, bill.getId())));
			return bill;
		}).when(target).getByIdDeep(any());
		var proxy = new ProxyFactory(target);
		proxy.setProxyTargetClass(true);
		proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),
				new AnnotationTransactionAttributeSource()));
		service = (MesWarehousePickingBillServiceImpl) proxy.getProxy();
		var task = new MesTaskEntity();
		task.setId(1L);
		task.setCode("TASK-1");
		task.setFactoryCode("F1");
		task.setTenantId(1L);
		task.setMatCode("PRODUCT-1");
		task.setMatName("Source product");
		task.setMatSpec("SPEC-1");
		task.setBomSnapshot("[{\"materialCode\":\"MAT-1\",\"quantity\":1}]");
		tasks.insert(task);
	}

	@AfterEach
	void cleanup() {
		SecurityContextHolder.clearContext();
		TenantContextHolder.clear();
		FactoryContextHolder.clear();
		db.execute("SHUTDOWN");
	}

	@Test
	void ordinaryMainMaterialUsesSourceTaskTenantAndFactory() {
		var input = input();
		input.setFactoryCode("SPOOF");
		input.setTenantId(99L);
		input.getDetailList().get(0).setFactoryCode("SPOOF");
		input.getDetailList().get(0).setTenantId(99L);

		assertTrue(service.saveDeep(input));

		var saved = bills.selectById(input.getId());
		var detail = details.selectById(input.getDetailList().get(0).getId());
		assertEquals("F1", saved.getFactoryCode());
		assertEquals(1L, saved.getTenantId());
		assertEquals("F1", detail.getFactoryCode());
		assertEquals(1L, detail.getTenantId());
		assertEquals("MAT-1", detail.getRequirementMatCode());
		assertEquals(0, BigDecimal.ONE.compareTo(detail.getRequirementMatNum()));
	}

	@ParameterizedTest
	@ValueSource(strings = {"DENIED", "NO_USER", "TENANT_MISMATCH", "TENANT_SKIP", "FACTORY_SKIP", "NO_FACTORY"})
	void unauthorizedCreationCannotWrite(String invalid) {
		switch (invalid) {
			case "DENIED" -> when(factories.check(any())).thenReturn(R.ok(false));
			case "NO_USER" -> SecurityContextHolder.clearContext();
			case "TENANT_MISMATCH" -> TenantContextHolder.setTenantId(2L);
			case "TENANT_SKIP" -> TenantContextHolder.setTenantSkip();
			case "FACTORY_SKIP" -> FactoryContextHolder.setFactorySkip();
			default -> FactoryContextHolder.clear();
		}

		assertThrows(CheckedException.class, () -> service.saveDeep(input()));

		assertEquals(0, count("mes_warehouse_picking_bill"));
		assertEquals(0, count("mes_warehouse_picking_bill_detail"));
		verifyZeroInteractions(wms);
	}

	@ParameterizedTest
	@ValueSource(strings = {"factory_code='F2'", "tenant_id=2"})
	void sourceTaskMustBelongToActualTenantAndFactory(String mutation) {
		db.update("update mes_task set " + mutation);

		assertThrows(CheckedException.class, () -> service.saveDeep(input()));

		assertEquals(0, count("mes_warehouse_picking_bill"));
		assertEquals(0, count("mes_warehouse_picking_bill_detail"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"update", "commit", "withdraw", "remove", "child"})
	void persistedFactoryControlsEveryMutation(String action) {
		fixture("F2");
		db.update("update mes_task set factory_code='F2'");
		if (action.equals("withdraw")) {
			db.update("update mes_warehouse_picking_bill set status=?", MesPickingBillStateEnum.COMMITTED.getCode());
		}

		assertThrows(CheckedException.class, () -> mutate(action));

		assertEquals(1, count("mes_warehouse_picking_bill"));
		assertEquals(1, count("mes_warehouse_picking_bill_detail"));
		verify(bills, never()).updateById(any(MesWarehousePickingBillEntity.class));
		verify(bills, never()).update(any(), any(Wrapper.class));
		verify(bills, never()).deleteBatchIds(any());
		verify(details, never()).updateById(any(MesWarehousePickingBillDetailEntity.class));
		verify(details, never()).deleteBatchIds(any());
		verifyZeroInteractions(wms);
	}

	@ParameterizedTest
	@ValueSource(strings = {"remove", "child"})
	void allBatchFactoriesAreCheckedBeforeAnyDeletion(String action) {
		fixture("F1");
		db.update("insert into mes_warehouse_picking_bill(id,tenant_id,factory_code,status,del_flag) values(20,1,'F2',?,'0')",
				MesPickingBillStateEnum.CREATED.getCode());
		db.update("insert into mes_warehouse_picking_bill_detail(id,bill_id,tenant_id,factory_code,del_flag) values(21,20,1,'F2','0')");

		assertThrows(CheckedException.class, () -> {
			if (action.equals("remove")) {
				service.removeDeep(new Long[]{10L, 20L});
			} else {
				service.removeChild(new Long[]{11L, 21L});
			}
		});

		assertEquals(2, count("mes_warehouse_picking_bill"));
		assertEquals(2, count("mes_warehouse_picking_bill_detail"));
		verify(bills, never()).deleteBatchIds(any());
		verify(details, never()).deleteBatchIds(any());
	}

	@ParameterizedTest
	@ValueSource(strings = {"FALSE", "FAILED_CODE", "NO_RESPONSE"})
	void explicitWmsRejectionOrMissingResponseCannotCommit(String receipt) {
		fixture("F1");
		when(wms.add(any())).thenReturn(receipt.equals("NO_RESPONSE") ? null
				: receipt.equals("FAILED_CODE") ? R.ok(true).setCode(1) : R.ok(false));

		assertThrows(CheckedException.class, () -> service.commitBill(10L));

		assertEquals(MesPickingBillStateEnum.CREATED.getCode(), bills.selectById(10L).getStatus());
		assertNull(details.selectById(11L).getRequirementMatCode());
	}

	@Test
	void acceptedWmsNotificationCommitsCanonicalFactoryAndDemand() {
		fixture("F1");

		assertTrue(assertDoesNotThrow(() -> service.commitBill(10L)));

		assertEquals(MesPickingBillStateEnum.COMMITTED.getCode(), bills.selectById(10L).getStatus());
		assertEquals("MAT-1", details.selectById(11L).getRequirementMatCode());
		verify(wms).add(argThat(notice -> "F1".equals(notice.getFactoryCode())
				&& Long.valueOf(1).equals(notice.getTenantId())
				&& notice.getDetailList().size() == 1
				&& "F1".equals(notice.getDetailList().get(0).getFactoryCode())));
	}

	@ParameterizedTest
	@ValueSource(strings = {"HEADER_INSERT", "DETAIL_INSERT", "HEADER_UPDATE", "DETAIL_UPDATE", "COMMIT_UPDATE"})
	void missingWriteConfirmationFailsAndRollsBack(String failure) {
		if (failure.endsWith("INSERT")) {
			if (failure.equals("HEADER_INSERT")) {
				doReturn(0).when(bills).insert(any(MesWarehousePickingBillEntity.class));
			} else {
				doReturn(0).when(details).insert(any(MesWarehousePickingBillDetailEntity.class));
			}

			assertThrows(CheckedException.class, () -> service.saveDeep(input()));

			assertEquals(0, count("mes_warehouse_picking_bill"));
			assertEquals(0, count("mes_warehouse_picking_bill_detail"));
		} else {
			fixture("F1");
			if (failure.equals("HEADER_UPDATE")) {
				doReturn(0).when(bills).update(any(MesWarehousePickingBillEntity.class), any(Wrapper.class));
			} else if (failure.equals("DETAIL_UPDATE")) {
				doReturn(0).when(details).updateById(any(MesWarehousePickingBillDetailEntity.class));
			} else {
				doReturn(0).when(bills).update(any(), any(Wrapper.class));
			}

			assertThrows(CheckedException.class, () -> mutate(failure.equals("COMMIT_UPDATE") ? "commit" : "update"));

			assertNull(details.selectById(11L).getRequirementMatCode());
			assertEquals(MesPickingBillStateEnum.CREATED.getCode(), bills.selectById(10L).getStatus());
			verifyZeroInteractions(wms);
		}
	}


	@ParameterizedTest
	@ValueSource(strings = {"update", "commit", "withdraw", "remove", "child"})
	void revokedFactoryWriteAccessCannotMutateExistingBills(String action) {
		fixture("F1");
		when(factories.check(any())).thenReturn(R.ok(false));
		if (action.equals("withdraw")) {
			db.update("update mes_warehouse_picking_bill set status=?", MesPickingBillStateEnum.COMMITTED.getCode());
		}

		assertThrows(CheckedException.class, () -> mutate(action));

		assertEquals(1, count("mes_warehouse_picking_bill"));
		assertEquals(1, count("mes_warehouse_picking_bill_detail"));
		verify(bills, never()).updateById(any(MesWarehousePickingBillEntity.class));
		verify(bills, never()).update(any(), any(Wrapper.class));
		verify(bills, never()).deleteBatchIds(any());
		verify(details, never()).updateById(any(MesWarehousePickingBillDetailEntity.class));
		verify(details, never()).deleteBatchIds(any());
		verifyZeroInteractions(wms);
	}

	@ParameterizedTest
	@ValueSource(strings = {"FALSE", "NO_DATA", "NO_RESPONSE"})
	void onlyPositiveWmsVoidAcceptanceCanWithdraw(String receipt) {
		fixture("F1");
		db.update("update mes_warehouse_picking_bill set status=?", MesPickingBillStateEnum.COMMITTED.getCode());
		when(wms.voidObjByDocumentId(any(), any())).thenReturn(receipt.equals("NO_RESPONSE") ? null
				: R.ok(receipt.equals("FALSE") ? false : null));

		assertThrows(CheckedException.class, () -> service.withdraw(10L));

		assertEquals(MesPickingBillStateEnum.COMMITTED.getCode(), bills.selectById(10L).getStatus());
	}

	@ParameterizedTest
	@ValueSource(strings = {"HEADER_DELETE", "DETAIL_DELETE", "CHILD_DELETE", "WITHDRAW_UPDATE"})
	void unsuccessfulDeletionOrWithdrawalCannotReportSuccess(String failure) {
		fixture("F1");
		if (failure.equals("HEADER_DELETE")) {
			doReturn(0).when(bills).deleteBatchIds(any());
		} else if (failure.equals("DETAIL_DELETE")) {
			doReturn(0).when(details).delete(any(Wrapper.class));
		} else if (failure.equals("CHILD_DELETE")) {
			doReturn(0).when(details).deleteBatchIds(any());
		} else {
			db.update("update mes_warehouse_picking_bill set status=?", MesPickingBillStateEnum.COMMITTED.getCode());
			doReturn(0).when(bills).update(any(), any(Wrapper.class));
		}

		assertThrows(CheckedException.class, () -> mutate(failure.equals("WITHDRAW_UPDATE") ? "withdraw"
				: failure.equals("CHILD_DELETE") ? "child" : "remove"));

		assertEquals(1, count("mes_warehouse_picking_bill"));
		assertEquals(1, count("mes_warehouse_picking_bill_detail"));
	}


	@Test
	void editingCannotRewriteTheOriginalPickingActorOrTime() {
		fixture("F1");
		db.update("update mes_warehouse_picking_bill set picker='original',pick_time='2026-10-30 08:00:00'");
		var input = input();
		input.setId(10L);
		input.setPicker("forged");
		input.setPickTime(LocalDateTime.of(2000, 1, 1, 0, 0));
		input.getDetailList().get(0).setId(11L);

		assertTrue(service.updateDeep(input));

		assertEquals("original", bills.selectById(10L).getPicker());
		assertEquals(LocalDateTime.of(2026, 10, 30, 8, 0), bills.selectById(10L).getPickTime());
	}


	@Test
	void legacyWmsCodeZeroWithNoBooleanDataStillCommits() {
		fixture("F1");
		// Existing /produce/picking/outNotice/add returns R.ok() after persisting the notice.
		when(wms.add(any())).thenReturn(R.ok(null));

		assertTrue(assertDoesNotThrow(() -> service.commitBill(10L)));

		assertEquals(MesPickingBillStateEnum.COMMITTED.getCode(), bills.selectById(10L).getStatus());
	}


	@ParameterizedTest
	@ValueSource(strings = {"update", "withdraw", "remove", "child"})
	void authorizedDraftOperationsRetainTheirNormalBehavior(String action) {
		fixture("F1");
		if (action.equals("withdraw")) {
			db.update("update mes_warehouse_picking_bill set status=?", MesPickingBillStateEnum.COMMITTED.getCode());
		}

		assertDoesNotThrow(() -> mutate(action));

		if (action.equals("remove")) {
			assertNull(bills.selectById(10L));
			assertNull(details.selectById(11L));
		} else if (action.equals("child")) {
			assertNotNull(bills.selectById(10L));
			assertNull(details.selectById(11L));
		} else {
			assertEquals(MesPickingBillStateEnum.CREATED.getCode(), bills.selectById(10L).getStatus());
		}
	}

	@Test
	void internalWmsCallbackDoesNotRequireAnEndUserPrincipal() {
		fixture("F1");
		db.update("update mes_warehouse_picking_bill set status=?", MesPickingBillStateEnum.COMMITTED.getCode());
		SecurityContextHolder.clearContext();
		FactoryContextHolder.clear();
		var payload = new com.kitex.mom.mes.dto.MesPickingWmsOutBillBackDTO();
		payload.setId(10L);
		var notice = new MesWarehouseMaterialInBillEntity();
		notice.setWmsOutDetailId(100L);
		payload.setMatDetails(List.of(notice));

		assertTrue(service.wmsOutBillBack(payload));

		assertEquals(10L, notice.getBillId());
		assertEquals("F1", notice.getFactoryCode());
		verify(receipts).registerNotice(notice);
		verify(receipts).refreshPicking(10L);
		verifyZeroInteractions(factories);
	}


	@ParameterizedTest
	@ValueSource(strings = {"save", "update"})
	void productIdentityAlwaysComesFromTheLockedSourceTask(String action) {
		var input = input();
		input.setMatCode("FORGED-PRODUCT");
		input.setMatName("Forged product");
		input.setMatSpec("FORGED-SPEC");
		if (action.equals("update")) {
			fixture("F1");
			input.setId(10L);
			input.getDetailList().get(0).setId(11L);
		}

		assertTrue(action.equals("save") ? service.saveDeep(input) : service.updateDeep(input));

		var persisted = bills.selectById(input.getId());
		assertEquals("PRODUCT-1", persisted.getMatCode());
		assertEquals("Source product", persisted.getMatName());
		assertEquals("SPEC-1", persisted.getMatSpec());
	}


	@Test
	void editingClearsAnIncorrectProductSpecificationWhenTheSourceHasNone() {
		fixture("F1");
		db.update("update mes_task set mat_spec=null");
		db.update("update mes_warehouse_picking_bill set mat_spec='INCORRECT'");
		var input = input();
		input.setId(10L);
		input.setMatSpec("FORGED-SPEC");
		input.getDetailList().get(0).setId(11L);

		assertTrue(service.updateDeep(input));

		var persisted = bills.selectById(10L);
		assertEquals("PRODUCT-1", persisted.getMatCode());
		assertEquals("Source product", persisted.getMatName());
		assertNull(persisted.getMatSpec());
	}

	private void mutate(String action) {
		switch (action) {
			case "update" -> {
				var value = input();
				value.setId(10L);
				value.getDetailList().get(0).setId(11L);
				service.updateDeep(value);
			}
			case "commit" -> service.commitBill(10L);
			case "withdraw" -> service.withdraw(10L);
			case "remove" -> service.removeDeep(new Long[]{10L});
			case "child" -> service.removeChild(new Long[]{11L});
			default -> fail(action);
		}
	}

	private MesWarehousePickingBillEntity input() {
		var value = new MesWarehousePickingBillEntity();
		value.setTaskCode("TASK-1");
		value.setFactoryCode("F1");
		value.setTenantId(1L);
		var detail = new MesWarehousePickingBillDetailEntity();
		detail.setMatCode("MAT-1");
		detail.setMatNum(BigDecimal.ONE);
		detail.setWarehouseCode("W1");
		value.setDetailList(List.of(detail));
		return value;
	}

	private void fixture(String factory) {
		db.update("insert into mes_warehouse_picking_bill(id,task_code,bill_no,tenant_id,factory_code,status,del_flag) "
				+ "values(10,'TASK-1','PICK-10',1,?,?,'0')", factory, MesPickingBillStateEnum.CREATED.getCode());
		db.update("insert into mes_warehouse_picking_bill_detail(id,bill_id,mat_code,mat_num,warehouse_code,tenant_id,factory_code,del_flag) "
				+ "values(11,10,'MAT-1',1,'W1',1,?,'0')", factory);
		clearInvocations(bills, details);
	}

	private int count(String table) {
		return db.queryForObject("select count(*) from " + table, Integer.class);
	}

	private void createTable(MybatisConfiguration config, Class<?> type) {
		TableInfoHelper.initTableInfo(new MapperBuilderAssistant(config, "picking-ddl"), type);
		var info = TableInfoHelper.getTableInfo(type);
		var ddl = new StringBuilder("create table ").append(info.getTableName()).append("(id bigint primary key");
		for (var field : info.getFieldList()) {
			Class<?> property = field.getPropertyType();
			String sql = property == Long.class ? "bigint" : property == Integer.class ? "integer"
					: property == Boolean.class ? "boolean" : property == BigDecimal.class ? "decimal(24,8)"
					: property == LocalDateTime.class || property == java.util.Date.class ? "timestamp" : "varchar(64000)";
			ddl.append(',').append(field.getColumn()).append(' ').append(sql);
			if (field.getColumn().equals("del_flag")) {
				ddl.append(" default '0'");
			}
		}
		db.execute(ddl.append(')').toString());
	}
}
