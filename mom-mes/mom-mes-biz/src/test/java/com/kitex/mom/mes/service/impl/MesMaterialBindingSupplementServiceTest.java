package com.kitex.mom.mes.service.impl;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.mom.mdm.dto.MdmMaterialMeasureDTO;
import com.kitex.mom.mdm.feign.RemoteMaterialService;
import com.kitex.mom.mes.dto.*;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.mapper.*;
import com.kitex.mom.mes.service.*;
import com.kitex.mom.mes.service.impl.material.MesProductionFeedAllocationPlanner;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real H2/MyBatis persistence, Spring transactions and allocation kernel; PG locks/ready, birth verifier and original start are explicit boundaries. */
class MesMaterialBindingSupplementServiceTest {
	private JdbcTemplate db;
	private MesStartMaterialBindingService initialService;
	private MesMaterialBindingSupplementService service;
	private MesMaterialBindingSupplementMapper supplementMapper;
	private MesTerminalExecutionGuard terminal;
	private RemoteMaterialService materials;
	private MesStartMaterialBindingReader reader;
	private MesWarehouseFeedBirthService births;
	private MesTaskArrangeService arrangements;
	private MesStartMaterialBindingMapper mapper;
	private final MesPhysicalTransformCodec physical = new MesPhysicalTransformCodec();
	private final MesStartMaterialBindingCodec codec = new MesStartMaterialBindingCodec(physical);
	private static final String ROUTE = "[{\"routeNodeCode\":\"N1\",\"processCode\":\"R1\",\"mdmProcessProcedureMatList\":[{\"materialCode\":\"M1\",\"unit\":\"kg\",\"quantity\":1}]}]";

	@BeforeEach
	void setup() throws Exception {
		TenantContextHolder.setTenantId(1L);
		FactoryContextHolder.setFactoryCode("F1");
		var user = mock(MomUser.class);
		when(user.getId()).thenReturn(7L);
		when(user.getTenantId()).thenReturn(1L);
		when(user.getUsername()).thenReturn("worker-7");
		SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, "N/A", List.of()));
		var source = new DriverManagerDataSource("jdbc:h2:mem:binding-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
		source.setDriverClassName("org.h2.Driver");
		db = new JdbcTemplate(source);
		db.execute("CREATE SCHEMA mom_mes");
		db.execute("CREATE TABLE mom_mes.mes_start_material_binding(id bigint primary key,tenant_id bigint,factory_code varchar,arrange_id bigint unique,"
				+ "arrange_code varchar,task_code varchar,produce_task_code varchar,route_node_code varchar,product_batch_no varchar,request_id varchar,"
				+ "request_hash varchar,preview_fingerprint varchar,binding_json clob,binding_fingerprint varchar,material_gate_record_id bigint,"
				+ "operator_id bigint,operator_code varchar,started_at timestamp(6),create_by varchar,create_time timestamp(6),del_flag varchar,"
				+ "unique(tenant_id,factory_code,request_id))");
		db.execute("CREATE TABLE mom_mes.mes_execution_command_cancellation(id bigint primary key,tenant_id bigint,factory_code varchar,"
				+ "command_kind varchar,request_id varchar,request_hash varchar,actor_id bigint,create_by varchar,create_time timestamp(6),"
				+ "del_flag varchar)");
		db.execute("CREATE TABLE mom_mes.mes_task_arrange(id bigint primary key,tenant_id bigint,factory_code varchar,code varchar,task_code varchar,"
				+ "produce_task_code varchar,source_route_node_code varchar,route_code varchar,batch_no varchar,mat_code varchar,report_mode varchar,"
				+ "status varchar,start_time timestamp(6),material_gate_record_id bigint,notice_num numeric(28,8),complete_num numeric(28,8),"
				+ "del_flag varchar)");
		db.execute("CREATE TABLE mom_mes.mes_task(id bigint primary key,tenant_id bigint,factory_code varchar,code varchar,type varchar,"
				+ "bom_snapshot varchar,process_route_snapshot varchar,del_flag varchar)");
		db.execute("CREATE TABLE mom_mes.mes_task_report(tenant_id bigint,factory_code varchar,arrange_code varchar)");
		db.execute("CREATE TABLE mom_mes.mes_task_report_unqualified(tenant_id bigint,factory_code varchar,arrange_code varchar)");
		db.execute("CREATE TABLE mom_mes.mes_warehouse_feed(id bigint primary key,tenant_id bigint,factory_code varchar,task_code varchar,"
				+ "produce_task_code varchar,arrange_code varchar,mat_code varchar,mat_unit varchar,requirement_mat_code varchar,mat_batch varchar,"
				+ "feed_num numeric(28,8),used_num numeric(28,8),substitute_ratio numeric(28,8),substitute_flag boolean,is_lock boolean,del_flag varchar)");
		db.execute("CREATE TABLE mom_mes.mes_material_binding_supplement(id bigint primary key,tenant_id bigint,factory_code varchar,"
				+ "arrange_id bigint,arrange_code varchar,task_code varchar,produce_task_code varchar,route_node_code varchar,"
				+ "product_batch_no varchar,initial_binding_id bigint,sequence_no integer,previous_fingerprint varchar,request_id varchar,"
				+ "request_hash varchar,preview_fingerprint varchar,supplement_json clob,supplement_fingerprint varchar,operator_id bigint,"
				+ "operator_code varchar,confirmed_at timestamp(6),create_by varchar,create_time timestamp(6),del_flag varchar,"
				+ "unique(tenant_id,factory_code,arrange_id,sequence_no),unique(tenant_id,factory_code,request_id))");
		db.update("INSERT INTO mom_mes.mes_task_arrange VALUES(10,1,'F1','A1','T1','P1','N1','R1','B1','PRODUCT1','QUANTITY','RECEIVE',null,null,2,0,'0')");
		db.update("INSERT INTO mom_mes.mes_task VALUES(20,1,'F1','T1','PRODUCT','[]',?,'0')", ROUTE);
		db.update("INSERT INTO mom_mes.mes_warehouse_feed VALUES(30,1,'F1','T1','P1','A1','M1','kg','M1','MB1',3,0,1,false,false,'0')");
		db.execute("ALTER TABLE mom_mes.mes_task_arrange ADD COLUMN worker_code varchar");
		db.execute("ALTER TABLE mom_mes.mes_warehouse_feed ADD COLUMN mat_valid_end_date date");
		var configuration = new Configuration();
		configuration.setMapUnderscoreToCamelCase(true);
		configuration.setEnvironment(new Environment("binding", new SpringManagedTransactionFactory(), source));
		configuration.addMapper(MesStartMaterialBindingMapper.class);
		configuration.addMapper(MesExecutionCommandCancellationMapper.class);
		configuration.addMapper(MesMaterialBindingSupplementMapper.class);
		var sql = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration));
		var actual = sql.getMapper(MesStartMaterialBindingMapper.class);
		mapper = mock(MesStartMaterialBindingMapper.class, invocation -> {
			if (invocation.getMethod().getName().equals("ready")) {
				return true;
			}
			if (invocation.getMethod().getName().equals("lockRequest")) {
				return 1;
			}
			try {
				return invocation.getMethod().invoke(actual, invocation.getArguments());
			} catch (java.lang.reflect.InvocationTargetException error) {
				throw error.getCause();
			}
		});
		var access = MesFactoryAccessTestSupport.authorized();
		var actualSupplement = sql.getMapper(MesMaterialBindingSupplementMapper.class);
		supplementMapper = mock(MesMaterialBindingSupplementMapper.class, invocation -> {
			if (invocation.getMethod().getName().equals("ready")) {
				return true;
			}
			if (invocation.getMethod().getName().equals("lockRequest")) {
				return 1;
			}
			try {
				return invocation.getMethod().invoke(actualSupplement, invocation.getArguments());
			} catch (java.lang.reflect.InvocationTargetException error) {
				throw error.getCause();
			}
		});
		var pools = new MesMaterialBindingPoolReader(supplementMapper, new MesMaterialBindingSupplementCodec(physical), codec, physical);
		reader = new MesStartMaterialBindingReader(mapper, codec, access, pools);
		births = mock(MesWarehouseFeedBirthService.class);
		var birth = new MesWarehouseFeedBirthEntity();
		birth.setId(40L);
		birth.setTenantId(1L);
		birth.setFactoryCode("F1");
		birth.setFeedId(30L);
		birth.setTaskId(20L);
		birth.setArrangeId(10L);
		birth.setEvidenceFingerprint("a".repeat(64));
		birth.setSourceCount(1);
		originalBirthFields(birth, 30L);
		when(births.evidence(30L)).thenReturn(new MesWarehouseFeedBirthDTO("RECORDED", birth, List.of(new MesWarehouseFeedBirthLineEntity())));
		materials = mock(RemoteMaterialService.class);
		var measure = new MdmMaterialMeasureDTO();
		measure.setMaterialId("50");
		measure.setMaterialCode("M1");
		measure.setTenantId("1");
		measure.setFactoryCode("F1");
		measure.setBasicUnit("kg");
		measure.setEnabled(true);
		when(materials.productionMeasure("M1")).thenReturn(R.ok(measure));
		var product = new MdmMaterialMeasureDTO();
		product.setMaterialId("51");
		product.setMaterialCode("PRODUCT1");
		product.setTenantId("1");
		product.setFactoryCode("F1");
		product.setBasicUnit("EA");
		product.setEnabled(true);
		when(materials.productionMeasure("PRODUCT1")).thenReturn(R.ok(product));
		var modes = mock(MesMaterialConsumptionModeResolver.class);
		when(modes.resolve(any())).thenReturn(MesMaterialConsumptionModeResolver.Mode.FEED);
		var templates = mock(MesExecutionTemplateService.class);
		when(templates.contract(any(MesTaskArrangeEntity.class))).thenReturn(new MesExecutionContractDTO());
		var locks = mock(MesExecutionTaskLock.class);
		when(locks.arrange(anyLong())).thenAnswer(i -> actual.arrange(1L, "F1", i.getArgument(0)));
		arrangements = mock(MesTaskArrangeService.class);
		doAnswer(i -> {
			db.update("UPDATE mom_mes.mes_task_arrange SET status='PRODUCT',start_time=TIMESTAMP '2026-10-23 10:00:00',material_gate_record_id=60 WHERE id=10");
			return null;
		}).when(arrangements).startWithMaterialSources(anyLong(), anyList());
		var cancel = new MesExecutionCommandCancellationService(access, sql.getMapper(MesExecutionCommandCancellationMapper.class));
		var target = new MesStartMaterialBindingService(mapper, reader, codec, physical, access, locks, arrangements,
				new MesStartMaterialSourceVerifier(births, materials, codec, physical), modes, templates, new MesProductionFeedAllocationPlanner(physical), cancel);
		var proxy = new ProxyFactory(target);
		proxy.setProxyTargetClass(true);
		var transactions = new TransactionInterceptor();
		transactions.setTransactionManager(new DataSourceTransactionManager(source));
		transactions.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
		proxy.addAdvice(transactions);
		initialService = (MesStartMaterialBindingService) proxy.getProxy();
		terminal = mock(MesTerminalExecutionGuard.class);
		var supplementTarget = new MesMaterialBindingSupplementService(supplementMapper, reader, mapper, pools, terminal,
				new MesMaterialBindingSupplementCodec(physical), physical, access, locks,
				new MesStartMaterialSourceVerifier(births, materials, codec, physical), modes, templates,
				new MesProductionFeedAllocationPlanner(physical), cancel);
		var supplementProxy = new ProxyFactory(supplementTarget);
		supplementProxy.setProxyTargetClass(true);
		supplementProxy.addAdvice(transactions);
		service = (MesMaterialBindingSupplementService) supplementProxy.getProxy();
		initialService.execute(initialCommand(initialService.preview(initialQuery()).previewFingerprint()));
		db.update("UPDATE mom_mes.mes_task_arrange SET status='PAUSE',worker_code='worker-7'");
		db.update("UPDATE mom_mes.mes_warehouse_feed SET used_num=feed_num WHERE id=30");
		db.update("INSERT INTO mom_mes.mes_warehouse_feed SELECT 31,tenant_id,factory_code,task_code,produce_task_code,arrange_code,mat_code,mat_unit,"
				+ "requirement_mat_code,mat_batch,3,0,substitute_ratio,substitute_flag,is_lock,del_flag,mat_valid_end_date FROM mom_mes.mes_warehouse_feed WHERE id=30");
		birth(31L);
	}

	@AfterEach
	void clear() {
		SecurityContextHolder.clearContext();
		TenantContextHolder.clear();
		FactoryContextHolder.clear();
	}
	@Test
	void exhaustedOriginalCanAppendWithoutNewStartOrStockMutation() {
		var preview = service.preview(query("31"));
		assertEquals(preview.previewFingerprint(), service.preview(query("31")).previewFingerprint());

		var receipt = service.execute(command(preview));

		assertEquals("APPLIED", receipt.state());
		assertEquals(Set.of(30L, 31L), reader.sourceIds(mapper.arrange(1L, "F1", 10L)));
		assertEquals("PAUSE", db.queryForObject("SELECT status FROM mom_mes.mes_task_arrange", String.class));
		assertEquals(new BigDecimal("0.00000000"), db.queryForObject("SELECT used_num FROM mom_mes.mes_warehouse_feed WHERE id=31", BigDecimal.class));
		verify(arrangements, times(1)).startWithMaterialSources(anyLong(), anyList());
		var pool = service.arrangement("10").pool();
		assertEquals("1", pool.revision());
		assertEquals(List.of("30", "31"), pool.feedIds());
		assertEquals("3", ((Map<?, ?>) ((List<?>) receipt.supplement().get("existingSources")).get(0)).get("usedQuantity"));
	}

	@Test
	void replayAndOriginalQuerySurviveResumedCompletedAndWorkerChanges() {
		var raw = command(service.preview(query("31")));
		service.execute(raw);
		db.update("UPDATE mom_mes.mes_task_arrange SET status='COMPLETE',complete_num=notice_num,worker_code='other'");

		assertEquals("APPLIED", service.execute(raw).state());
		assertEquals("APPLIED", service.cancel(raw).state());
		assertEquals("APPLIED", service.request("supplement-0001").state());
		assertEquals(1, db.queryForObject("SELECT count(*) FROM mom_mes.mes_material_binding_supplement", Integer.class));
	}

	@Test
	void cancelledOriginalPermanentlyBlocksLatePostAndHasSeparateKind() {
		var raw = command(service.preview(query("31")));

		assertEquals("CANCELLED", service.cancel(raw).state());
		assertThrows(CheckedException.class, () -> service.execute(raw));
		assertEquals("CANCELLED", service.request("supplement-0001").state());
		assertEquals("MATERIAL_BINDING_SUPPLEMENT", db.queryForObject("SELECT command_kind FROM mom_mes.mes_execution_command_cancellation", String.class));
		assertEquals(0, db.queryForObject("SELECT count(*) FROM mom_mes.mes_material_binding_supplement", Integer.class));
	}

	@Test
	void oldLockedAndExpiredMembersAreRetainedButNeverProvideCoverage() {
		db.update("UPDATE mom_mes.mes_warehouse_feed SET used_num=0,is_lock=true WHERE id=30");
		db.update("UPDATE mom_mes.mes_warehouse_feed SET used_num=2 WHERE id=31");

		assertThrows(CheckedException.class, () -> service.preview(query("31")));
		db.update("UPDATE mom_mes.mes_warehouse_feed SET used_num=0 WHERE id=31");
		assertNotNull(service.preview(query("31")));
		db.update("UPDATE mom_mes.mes_warehouse_feed SET is_lock=false,mat_valid_end_date=DATE '2000-01-01' WHERE id=30");
		db.update("UPDATE mom_mes.mes_warehouse_feed SET used_num=2 WHERE id=31");
		assertThrows(CheckedException.class, () -> service.preview(query("31")));
	}

	@Test
	void rejectsEveryBadNewIdEvenIfCurrentPoolCoversEverything() {
		db.update("UPDATE mom_mes.mes_warehouse_feed SET used_num=0 WHERE id=30");
		assertThrows(CheckedException.class, () -> service.preview(query("31", "999")));
		db.update("UPDATE mom_mes.mes_warehouse_feed SET used_num=3 WHERE id=31");
		assertThrows(CheckedException.class, () -> service.preview(query("31")));
		db.update("UPDATE mom_mes.mes_warehouse_feed SET used_num=0,substitute_ratio=2 WHERE id=31");
		assertThrows(CheckedException.class, () -> service.preview(query("31")));
		db.update("UPDATE mom_mes.mes_warehouse_feed SET substitute_ratio=1 WHERE id=31");
		when(births.evidence(31L)).thenReturn(new MesWarehouseFeedBirthDTO("HISTORICAL_UNPROVEN", null, List.of()));
		assertThrows(CheckedException.class, () -> service.preview(query("31")));
	}

	@Test
	void unknownOldBalanceAndChangedOriginalIdentityFailClosed() {
		db.update("UPDATE mom_mes.mes_warehouse_feed SET used_num=null WHERE id=30");
		assertThrows(CheckedException.class, () -> service.preview(query("31")));
		db.update("UPDATE mom_mes.mes_warehouse_feed SET used_num=3,mat_batch='CHANGED' WHERE id=30");
		assertThrows(CheckedException.class, () -> service.preview(query("31")));
	}

	@Test
	void balancesEligibilityRemainingAndPoolDriftRejectOriginalPreview() {
		var raw = command(service.preview(query("31")));
		db.update("UPDATE mom_mes.mes_warehouse_feed SET used_num=0,is_lock=true WHERE id=30");
		assertThrows(CheckedException.class, () -> service.execute(raw));
		db.update("UPDATE mom_mes.mes_warehouse_feed SET used_num=3,is_lock=false WHERE id=30");
		db.update("UPDATE mom_mes.mes_task_arrange SET complete_num=1");
		assertThrows(CheckedException.class, () -> service.execute(raw));
		db.update("UPDATE mom_mes.mes_task_arrange SET complete_num=0");
		service.execute(raw);
		assertThrows(CheckedException.class, () -> service.execute(raw.replace("supplement-0001", "supplement-0002")));
	}

	@Test
	void appendTwoChainsAndDetectsTamperedMiddleWithoutInitialFallback() {
		service.execute(command(service.preview(query("31"))));
		db.update("INSERT INTO mom_mes.mes_warehouse_feed SELECT 32,tenant_id,factory_code,task_code,produce_task_code,arrange_code,mat_code,mat_unit,"
				+ "requirement_mat_code,mat_batch,3,0,substitute_ratio,substitute_flag,is_lock,del_flag,mat_valid_end_date FROM mom_mes.mes_warehouse_feed WHERE id=31");
		birth(32L);
		var second = command(service.preview(query("32"))).replace("supplement-0001", "supplement-0002");
		service.execute(second);

		assertEquals(List.of("30", "31", "32"), service.arrangement("10").pool().feedIds());
		assertEquals("2", service.arrangement("10").pool().revision());
		db.update("UPDATE mom_mes.mes_material_binding_supplement SET supplement_json='{}' WHERE sequence_no=1");
		assertThrows(CheckedException.class, () -> reader.sourceIds(mapper.arrange(1L, "F1", 10L)));
	}

	@Test
	void scopeEmployeeTerminalEngineeringUnknownRemainingAndStartTimeAreGuarded() {
		db.update("UPDATE mom_mes.mes_task_arrange SET worker_code='someone-else'");
		assertThrows(CheckedException.class, () -> service.preview(query("31")));
		db.update("UPDATE mom_mes.mes_task_arrange SET worker_code='worker-7',complete_num=null");
		assertThrows(CheckedException.class, () -> service.preview(query("31")));
		db.update("UPDATE mom_mes.mes_task_arrange SET complete_num=0,status='PRODUCT'");
		assertThrows(CheckedException.class, () -> service.preview(query("31")));
		db.update("UPDATE mom_mes.mes_task_arrange SET status='PAUSE'");
		doThrow(new CheckedException("terminal unresolved")).when(terminal).assertUnoccupied(any());
		assertThrows(CheckedException.class, () -> service.preview(query("31")));
		doNothing().when(terminal).assertUnoccupied(any());
		db.update("UPDATE mom_mes.mes_task SET bom_snapshot='[{}]'");
		assertThrows(CheckedException.class, () -> service.preview(query("31")));
	}

	@Test
	void missingReadyDuplicateSelectionCrossOrgAndNecessaryWriteFailWithoutChangingPool() {
		assertThrows(CheckedException.class, () -> service.preview(query("31", "31")));
		assertThrows(CheckedException.class, () -> service.preview(query("30")));
		var raw = command(service.preview(query("31")));
		assertThrows(CheckedException.class, () -> service.execute(raw.replace("\"expectedActorId\":\"7\"", "\"expectedActorId\":\"8\"")));
		when(supplementMapper.ready()).thenReturn(false);
		assertThrows(CheckedException.class, () -> reader.sourceIds(mapper.arrange(1L, "F1", 10L)));
		when(supplementMapper.ready()).thenReturn(true);
		db.execute("ALTER TABLE mom_mes.mes_material_binding_supplement ADD CONSTRAINT no_supplement CHECK(id<0)");
		assertThrows(RuntimeException.class, () -> service.execute(raw));
		assertEquals(List.of("30"), service.arrangement("10").pool().feedIds());
	}

	@Test
	void nullableOriginalBomRemainsCompatibleWithDefaultMainMaterialPolicy() {
		var original = db.queryForMap("SELECT binding_json,binding_fingerprint FROM mom_mes.mes_start_material_binding");
		String oldJson = original.get("BINDING_JSON").toString();
		String json = oldJson.replace("\"bomSnapshot\":\"[]\"", "\"bomSnapshot\":null");
		db.update("UPDATE mom_mes.mes_task SET bom_snapshot=null");
		db.update("UPDATE mom_mes.mes_start_material_binding SET binding_json=?,binding_fingerprint=?", json,
				physical.fingerprint(MesStartMaterialBindingCodec.VERSION, json));

		assertEquals("APPLIED", service.execute(command(service.preview(query("31")))).state());
	}

	@Test
	void replayAndQueryVerifyEntireChainEvenAfterSuccess() {
		var raw = command(service.preview(query("31")));
		service.execute(raw);
		db.update("UPDATE mom_mes.mes_material_binding_supplement SET previous_fingerprint=?", "c".repeat(64));

		assertThrows(CheckedException.class, () -> service.execute(raw));
		assertThrows(CheckedException.class, () -> service.request("supplement-0001"));
	}

	@Test
	void originalStartTimestampDriftAndGapRejectWholePool() {
		service.execute(command(service.preview(query("31"))));
		db.update("UPDATE mom_mes.mes_task_arrange SET start_time=TIMESTAMP '2026-10-23 10:01:00'");
		assertThrows(CheckedException.class, () -> service.arrangement("10"));
		db.update("UPDATE mom_mes.mes_task_arrange SET start_time=TIMESTAMP '2026-10-23 10:00:00'");
		db.update("UPDATE mom_mes.mes_material_binding_supplement SET sequence_no=2");
		assertThrows(CheckedException.class, () -> reader.sourceIds(mapper.arrange(1L, "F1", 10L)));
	}

	@Test
	void changedProductUnitCannotReinterpretTheOriginalPlannedQuantity() {
		materials.productionMeasure("PRODUCT1").getData().setBasicUnit("kg");

		assertThrows(CheckedException.class, () -> service.preview(query("31")));
		assertFalse(service.preparation("10").canSupplement());
		assertEquals(0, db.queryForObject("SELECT count(*) FROM mom_mes.mes_material_binding_supplement", Integer.class));
	}

	@Test
	void newlySelectedExpiryMetadataDriftRejectsStillEligiblePreview() {
		db.update("UPDATE mom_mes.mes_warehouse_feed SET mat_valid_end_date=DATE '2099-01-01' WHERE id=31");
		var raw = command(service.preview(query("31")));
		db.update("UPDATE mom_mes.mes_warehouse_feed SET mat_valid_end_date=DATE '2099-02-01' WHERE id=31");

		assertThrows(CheckedException.class, () -> service.execute(raw));
	}

	@Test
	void preparationCannotOfferNewSourcesWhenOldBalanceUnknown() {
		db.update("UPDATE mom_mes.mes_warehouse_feed SET used_num=null WHERE id=30");

		assertFalse(service.preparation("10").canSupplement());
	}

	@Test
	void cumulativeUnionAndHistoryCapacityCannotTruncate() {
		var ids = new ArrayList<String>();
		for (long id = 31; id <= 130; id++) {
			ids.add(Long.toString(id));
		}
		var tooMany = new MesMaterialBindingSupplementDTO.Query();
		tooMany.setArrangeId("10");
		tooMany.setAddedFeedIds(ids);
		assertTrue(assertThrows(CheckedException.class, () -> service.preview(tooMany)).getMessage().contains("100"));
	}

	@Test
	void successReplayAndQueryCannotBypassDamagedInitialOrSiblingHistory() {
		var raw = command(service.preview(query("31")));
		service.execute(raw);
		db.update("UPDATE mom_mes.mes_start_material_binding SET binding_json='{}'");

		assertThrows(CheckedException.class, () -> service.execute(raw));
		assertThrows(CheckedException.class, () -> service.request("supplement-0001"));
		assertThrows(CheckedException.class, () -> service.cancel(raw));
	}

	@Test
	void historyOverCapacityCannotBecomeTruncatedInitialPool() {
		service.execute(command(service.preview(query("31"))));
		var row = supplementMapper.byArrange(1L, "F1", 10L).get(0);
		when(supplementMapper.byArrange(1L, "F1", 10L)).thenReturn(Collections.nCopies(51, row));

		assertThrows(CheckedException.class, () -> reader.sourceIds(mapper.arrange(1L, "F1", 10L)));
	}

	@Test
	void spentOldMemberKeepsFrozenIdentityAfterMaterialMasterIsDisabled() {
		when(materials.productionMeasure("M1")).thenReturn(R.ok(null));
		var verifier = new MesStartMaterialSourceVerifier(births, materials, codec, physical);
		var arrange = mapper.arrange(1L, "F1", 10L);
		var task = mapper.tasks(1L, "F1", "T1").get(0);
		var spent = mapper.selected(1L, "F1", List.of(30L), false).get(0);

		assertEquals("kg", verifier.source(1L, "F1", arrange, task, spent, false).unit());
		var newlySelected = mapper.selected(1L, "F1", List.of(31L), false).get(0);
		assertThrows(CheckedException.class, () -> verifier.source(1L, "F1", arrange, task, newlySelected, true));
	}

	@Test
	void selectedSourceCannotBorrowNativeBirthWithDifferentOriginalUnitOrQuantity() {
		var original = births.evidence(31L).birth();
		original.setUnit("DIFFERENT");
		original.setQuantity(new BigDecimal("99"));

		assertThrows(CheckedException.class, () -> service.preview(query("31")));
	}

	@Test
	void selectedSourceMustMatchNativeBirthFixedIdentityProjection() {
		var original = births.evidence(31L).birth();
		original.setFeedSnapshot(original.getFeedSnapshot().replace("\"mat_code\":\"M1\"", "\"mat_code\":\"OTHER\""));

		assertThrows(CheckedException.class, () -> service.preview(query("31")));
	}

	private void birth(long feed) {
		var birth = new MesWarehouseFeedBirthEntity();
		birth.setId(feed + 100);
		birth.setTenantId(1L);
		birth.setFactoryCode("F1");
		birth.setFeedId(feed);
		birth.setTaskId(20L);
		birth.setArrangeId(10L);
		birth.setEvidenceFingerprint("b".repeat(64));
		birth.setSourceCount(1);
		originalBirthFields(birth, feed);
		when(births.evidence(feed)).thenReturn(new MesWarehouseFeedBirthDTO("RECORDED", birth, List.of(new MesWarehouseFeedBirthLineEntity())));
	}

	private MesStartMaterialBindingDTO.Query initialQuery() {
		var query = new MesStartMaterialBindingDTO.Query();
		query.setArrangeId("10");
		query.setFeedIds(List.of("30"));
		return query;
	}

	private String initialCommand(String fingerprint) {
		return "{\"contractVersion\":\"MES_START_MATERIAL_BINDING_COMMAND_V1\",\"requestId\":\"binding-0001\",\"arrangeId\":\"10\","
				+ "\"expectedTenantId\":\"1\",\"expectedFactoryCode\":\"F1\",\"expectedActorId\":\"7\",\"feedIds\":[\"30\"],"
				+ "\"expectedPreviewFingerprint\":\"" + fingerprint + "\"}";
	}

	private MesMaterialBindingSupplementDTO.Query query(String... ids) {
		var query = new MesMaterialBindingSupplementDTO.Query();
		query.setArrangeId("10");
		query.setAddedFeedIds(List.of(ids));
		return query;
	}

	private String command(MesMaterialBindingSupplementDTO.Preview preview) {
		return "{\"contractVersion\":\"MES_MATERIAL_BINDING_SUPPLEMENT_COMMAND_V1\",\"requestId\":\"supplement-0001\",\"arrangeId\":\"10\","
				+ "\"expectedTenantId\":\"1\",\"expectedFactoryCode\":\"F1\",\"expectedActorId\":\"7\",\"addedFeedIds\":[\""
				+ preview.selectedSources().get(0).feedId() + "\"],\"expectedPoolFingerprint\":\"" + preview.pool().fingerprint() + "\","
				+ "\"expectedPreviewFingerprint\":\"" + preview.previewFingerprint() + "\"}";
	}
	private void originalBirthFields(MesWarehouseFeedBirthEntity birth, long feedId) {
		var feed = mapper.selected(1L, "F1", List.of(feedId), false).get(0);
		birth.setQuantity(feed.getFeedNum());
		birth.setUnit(feed.getMatUnit());
		var facts = new LinkedHashMap<String, Object>();
		facts.put("id", feed.getId());
		facts.put("tenant_id", feed.getTenantId());
		facts.put("factory_code", feed.getFactoryCode());
		facts.put("task_code", feed.getTaskCode());
		facts.put("produce_task_code", feed.getProduceTaskCode());
		facts.put("arrange_code", feed.getArrangeCode());
		facts.put("mat_code", feed.getMatCode());
		facts.put("requirement_mat_code", feed.getRequirementMatCode());
		facts.put("mat_batch", feed.getMatBatch());
		facts.put("mat_barcode", feed.getMatBarcode());
		facts.put("mat_unit", feed.getMatUnit());
		facts.put("feed_num", feed.getFeedNum());
		facts.put("substitute_ratio", feed.getSubstituteRatio());
		facts.put("substitute_flag", feed.getSubstituteFlag());
		birth.setFeedSnapshot(physical.snapshot(facts));
	}

}
