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
class MesStartMaterialBindingServiceTest {
	private JdbcTemplate db;
	private MesStartMaterialBindingService service;
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
		db.update("INSERT INTO mom_mes.mes_task_arrange VALUES(10,1,'F1','A1','T1','P1','N1','R1','B1','PRODUCT1','QUANTITY','RECEIVE',null,null,2,0,'0')");
		db.update("INSERT INTO mom_mes.mes_task VALUES(20,1,'F1','T1','PRODUCT','[]',?,'0')", ROUTE);
		db.update("INSERT INTO mom_mes.mes_warehouse_feed VALUES(30,1,'F1','T1','P1','A1','M1','kg','M1','MB1',3,0,1,false,false,'0')");
		var configuration = new Configuration();
		configuration.setMapUnderscoreToCamelCase(true);
		configuration.setEnvironment(new Environment("binding", new SpringManagedTransactionFactory(), source));
		configuration.addMapper(MesStartMaterialBindingMapper.class);
		configuration.addMapper(MesExecutionCommandCancellationMapper.class);
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
		var supplementMapper = mock(MesMaterialBindingSupplementMapper.class);
		when(supplementMapper.ready()).thenReturn(true);
		when(supplementMapper.byArrange(anyLong(), anyString(), anyLong())).thenReturn(List.of());
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
		var materials = mock(RemoteMaterialService.class);
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
		service = (MesStartMaterialBindingService) proxy.getProxy();
	}

	@AfterEach
	void clear() {
		SecurityContextHolder.clearContext();
		TenantContextHolder.clear();
		FactoryContextHolder.clear();
	}

	@Test
	void realPlannerPreviewIsStableAndExecutePersistsAtomicStartWithExactPool() {
		var preview = service.preview(query("30"));
		assertEquals(preview.previewFingerprint(), service.preview(query("30")).previewFingerprint());

		var receipt = service.execute(command(preview.previewFingerprint()));

		assertEquals("APPLIED", receipt.state());
		assertEquals("B1", receipt.binding().get("productBatchNo"));
		assertEquals(Set.of(30L), reader.sourceIds(mapper.arrange(1L, "F1", 10L)));
		assertEquals("APPLIED", service.execute(command(preview.previewFingerprint())).state());
		verify(arrangements, times(1)).startWithMaterialSources(10L, List.of(30L));
		assertEquals(1, db.queryForObject("SELECT count(*) FROM mom_mes.mes_start_material_binding", Integer.class));
		assertThrows(CheckedException.class, () -> reader.assertNgAccounting(mapper.arrange(1L, "F1", 10L), "NONE"));
	}

	@Test
	void everySelectedIdMustExistAndHaveVerifiedBirth() {
		assertThrows(CheckedException.class, () -> service.preview(query("30", "999")));
		when(births.evidence(30L)).thenReturn(new MesWarehouseFeedBirthDTO("HISTORICAL_UNPROVEN", null, List.of()));

		assertThrows(CheckedException.class, () -> service.preview(query("30")));
		assertFalse(service.preparation("10").canBind());
		assertTrue(service.preparation("10").sources().isEmpty());
	}

	@Test
	void rejectedUnselectedFeedDoesNotDisableValidFullyCoveredSelection() {
		db.update("INSERT INTO mom_mes.mes_warehouse_feed SELECT 31,tenant_id,factory_code,task_code,produce_task_code,arrange_code,mat_code,mat_unit,"
				+ "requirement_mat_code,mat_batch,feed_num,used_num,substitute_ratio,substitute_flag,true,"
				+ "del_flag FROM mom_mes.mes_warehouse_feed WHERE id=30");

		var preparation = service.preparation("10");

		assertTrue(preparation.canBind());
		assertEquals(List.of("30"), preparation.sources().stream().map(MesStartMaterialBindingDTO.Source::feedId).toList());
		assertTrue(preparation.reasons().stream().anyMatch(reason -> reason.contains("31")));
		assertNotNull(service.preview(query("30")).previewFingerprint());
		assertThrows(CheckedException.class, () -> service.preview(query("30", "31")));
	}

	@Test
	void legacyQuantityFallbackAndAliasesExposeCanonicalModeToTheWorkbench() {
		db.execute("ALTER TABLE mom_mes.mes_task_arrange ADD COLUMN route_type varchar");
		db.update("UPDATE mom_mes.mes_task_arrange SET report_mode=null,route_type='BATCH_REPORT'");

		assertTrue(service.preparation("10").canBind());
		assertEquals("QUANTITY", service.preparation("10").reportMode());
		assertNotNull(service.preview(query("30")).previewFingerprint());

		for (String alias : List.of("BATCH_REPORT", "NUM", "UNITE_REPORT")) {
			db.update("UPDATE mom_mes.mes_task_arrange SET report_mode=?", alias);
			assertTrue(service.preparation("10").canBind());
			assertEquals("QUANTITY", service.preparation("10").reportMode());
		}
	}

	@Test
	void driftShortageLockedFeedAndUnknownUsedQuantityFailClosed() {
		var hash = service.preview(query("30")).previewFingerprint();
		db.update("UPDATE mom_mes.mes_warehouse_feed SET used_num=1");
		assertThrows(CheckedException.class, () -> service.execute(command(hash)));
		db.update("UPDATE mom_mes.mes_warehouse_feed SET feed_num=1,used_num=0");
		assertThrows(CheckedException.class, () -> service.preview(query("30")));
		db.update("UPDATE mom_mes.mes_warehouse_feed SET feed_num=3,is_lock=true");
		assertThrows(CheckedException.class, () -> service.preview(query("30")));
		db.update("UPDATE mom_mes.mes_warehouse_feed SET is_lock=false,used_num=null");
		assertThrows(CheckedException.class, () -> service.preview(query("30")));
	}

	@Test
	void necessaryBindingWriteAndOriginalStartFailuresRollbackEverything() {
		var hash = service.preview(query("30")).previewFingerprint();
		db.execute("ALTER TABLE mom_mes.mes_start_material_binding ADD CONSTRAINT no_binding CHECK(id<0)");

		assertThrows(RuntimeException.class, () -> service.execute(command(hash)));
		assertEquals("RECEIVE", db.queryForObject("SELECT status FROM mom_mes.mes_task_arrange", String.class));
		assertEquals(0, db.queryForObject("SELECT count(*) FROM mom_mes.mes_start_material_binding", Integer.class));
	}

	@Test
	void cancelledOriginalRejectsDelayedPostAndRemainsQueryable() {
		var raw = command(service.preview(query("30")).previewFingerprint());
		assertEquals("CANCELLED", service.cancel(raw).state());

		assertThrows(CheckedException.class, () -> service.execute(raw));
		assertEquals("CANCELLED", service.request("binding-0001").state());
		verify(arrangements, never()).startWithMaterialSources(anyLong(), anyList());
	}

	@Test
	void corruptedBindingAndMissingProtectionNeverBecomeLegacyUnbound() {
		assertNull(reader.sourceIds(mapper.arrange(1L, "F1", 10L)));
		service.execute(command(service.preview(query("30")).previewFingerprint()));
		db.update("UPDATE mom_mes.mes_start_material_binding SET binding_json='{}'");

		assertThrows(CheckedException.class, () -> reader.sourceIds(mapper.arrange(1L, "F1", 10L)));
		when(mapper.ready()).thenReturn(false);
		assertThrows(CheckedException.class, () -> reader.sourceIds(mapper.arrange(1L, "F1", 10L)));
	}

	@Test
	void originalStartFailureRollsBackItsMutationAndCreatesNoBinding() {
		var raw = command(service.preview(query("30")).previewFingerprint());
		doAnswer(invocation -> {
			db.update("UPDATE mom_mes.mes_task_arrange SET status='PRODUCT'");
			throw new CheckedException("actual SOP gate rejected");
		}).when(arrangements).startWithMaterialSources(anyLong(), anyList());

		assertThrows(CheckedException.class, () -> service.execute(raw));
		assertEquals("RECEIVE", db.queryForObject("SELECT status FROM mom_mes.mes_task_arrange", String.class));
		assertEquals(0, db.queryForObject("SELECT count(*) FROM mom_mes.mes_start_material_binding", Integer.class));
	}

	@Test
	void crossOrganizationAndSameRequestDifferentBodyNeverStart() {
		var raw = command(service.preview(query("30")).previewFingerprint());
		assertThrows(CheckedException.class, () -> service.execute(raw.replace("\"expectedActorId\":\"7\"", "\"expectedActorId\":\"8\"")));
		assertThrows(CheckedException.class, () -> service.execute(raw.replace("\"expectedFactoryCode\":\"F1\"", "\"expectedFactoryCode\":\"F2\"")));
		db.update("UPDATE mom_mes.mes_warehouse_feed SET task_code='T2'");
		assertThrows(CheckedException.class, () -> service.preview(query("30")));
		db.update("UPDATE mom_mes.mes_warehouse_feed SET task_code='T1'");
		service.execute(raw);

		assertThrows(CheckedException.class, () -> service.execute(raw.replace("[\"30\"]", "[\"31\"]")));
		verify(arrangements, times(1)).startWithMaterialSources(anyLong(), anyList());
	}

	@Test
	void capacityBlockNeverReturnsTruncatedSelectableSources() {
		for (long id = 31; id <= 130; id++) {
			db.update("INSERT INTO mom_mes.mes_warehouse_feed SELECT ?,tenant_id,factory_code,task_code,produce_task_code,arrange_code,mat_code,mat_unit,"
				+ "requirement_mat_code,mat_batch,feed_num,used_num,substitute_ratio,substitute_flag,is_lock,"
				+ "del_flag FROM mom_mes.mes_warehouse_feed WHERE id=30", id);
		}

		var preparation = service.preparation("10");

		assertFalse(preparation.canBind());
		assertTrue(preparation.sources().isEmpty());
		assertTrue(preparation.reasons().stream().anyMatch(reason -> reason.contains("100")));
	}

	@Test
	void exactPlannerRejectsEngineeringQuantityThatNeedsRounding() {
		db.update("UPDATE mom_mes.mes_task SET process_route_snapshot=?", ROUTE.replace("\"quantity\":1", "\"quantity\":0.000000001"));

		assertThrows(CheckedException.class, () -> service.preview(query("30")));
		assertEquals(0, db.queryForObject("SELECT count(*) FROM mom_mes.mes_start_material_binding", Integer.class));
	}

	private MesStartMaterialBindingDTO.Query query(String... ids) {
		var query = new MesStartMaterialBindingDTO.Query();
		query.setArrangeId("10");
		query.setFeedIds(List.of(ids));
		return query;
	}

	private String command(String fingerprint) {
		return "{\"contractVersion\":\"MES_START_MATERIAL_BINDING_COMMAND_V1\",\"requestId\":\"binding-0001\",\"arrangeId\":\"10\","
				+ "\"expectedTenantId\":\"1\",\"expectedFactoryCode\":\"F1\",\"expectedActorId\":\"7\",\"feedIds\":[\"30\"],"
				+ "\"expectedPreviewFingerprint\":\"" + fingerprint + "\"}";
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
