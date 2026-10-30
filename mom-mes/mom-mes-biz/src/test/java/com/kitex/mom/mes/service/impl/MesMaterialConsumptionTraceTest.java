package com.kitex.mom.mes.service.impl;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.mom.mes.dto.MesMaterialConsumptionTraceDTO.Query;
import com.kitex.mom.mes.mapper.MesMaterialConsumptionTraceMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Executes the production MyBatis SQL; H2 does not replace PostgreSQL deployment verification. */
class MesMaterialConsumptionTraceTest {

	private static final String NS = "com.kitex.mom.mes.mapper.MesMaterialConsumptionTraceMapper.";
	private JdbcTemplate db;
	private SqlSession session;

	@BeforeEach
	void setup() throws Exception {
		var source = new DriverManagerDataSource("jdbc:h2:mem:material-trace-" + UUID.randomUUID()
				+ ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
		source.setDriverClassName("org.h2.Driver");
		db = new JdbcTemplate(source);
		db.execute("CREATE SCHEMA mom_mes");
		var config = new Configuration();
		config.setEnvironment(new Environment("trace", new JdbcTransactionFactory(), source));
		try (var xml = getClass().getResourceAsStream("/mapper/MesMaterialConsumptionTraceMapper.xml")) {
			assertNotNull(xml, "Scoped material trace SQL is missing");
			new XMLMapperBuilder(xml, config, "material-trace.xml", config.getSqlFragments()).parse();
		}
		session = new SqlSessionFactoryBuilder().build(config).openSession(true);
		fixture();
	}

	@AfterEach
	void close() {
		SecurityContextHolder.clearContext();
		TenantContextHolder.clear();
		FactoryContextHolder.clear();
		if (session != null) {
			session.close();
		}
	}

	@Test
	void fourOriginalKindsAndCombinedRootAppearOnceDespiteMultipleShares() {
		var rows = rows(Map.of());

		assertEquals(4, rows.size());
		assertEquals(List.of("COMBINED_FEED", "GOOD_BACKFLUSH", "GOOD_FEED", "NG_FEED"),
				rows.stream().map(r -> r.get("source_kind")).toList());
		assertEquals(4L, total(Map.of()));
		assertEquals("9.12345678", rows.get(0).get("quantity"));
	}

	@Test
	void exactTaskAndOrganizationExcludeSimilarCodesAndSameCodesElsewhere() {
		db.update("INSERT INTO mom_mes.mes_task_arrange VALUES(2,1,'F1','A10','T10','P10')");
		db.update("INSERT INTO mom_mes.mes_task_arrange VALUES(3,2,'F1','A1','T1','P1')");
		db.update("INSERT INTO mom_mes.mes_task_arrange VALUES(4,1,'F2','A1','T1','P1')");
		db.update("INSERT INTO mom_mes.mes_report_material_consume SELECT 12,tenant_id,factory_code,report_id,report_request_id,'A10',feed_id,requirement_mat_code,mat_code,mat_batch,mat_barcode,consume_num,requirement_num,substitute_ratio,status,reverse_request_id,reverse_time,create_time FROM mom_mes.mes_report_material_consume WHERE id=11");
		db.update("INSERT INTO mom_mes.mes_report_material_consume SELECT 13,2,'F1',report_id,report_request_id,arrange_code,feed_id,requirement_mat_code,mat_code,mat_batch,mat_barcode,consume_num,requirement_num,substitute_ratio,status,reverse_request_id,reverse_time,create_time FROM mom_mes.mes_report_material_consume WHERE id=11");
		db.update("INSERT INTO mom_mes.mes_report_material_consume SELECT 14,1,'F2',report_id,report_request_id,arrange_code,feed_id,requirement_mat_code,mat_code,mat_batch,mat_barcode,consume_num,requirement_num,substitute_ratio,status,reverse_request_id,reverse_time,create_time FROM mom_mes.mes_report_material_consume WHERE id=11");

		assertEquals(4L, total(Map.of()));
		assertEquals(4, rows(Map.of()).size());
	}

	@Test
	void exactFiltersAndStablePaginationUseTheSamePredicate() {
		assertEquals(1L, total(Map.of("sourceKind", "GOOD_FEED", "batchNo", "B1", "actualMaterialCode", "M1", "requirementMaterialCode", "REQ1", "arrangeCode", "A1")));
		assertEquals(0L, total(Map.of("batchNo", "B")));
		var parameters = parameters(Map.of());
		parameters.put("limit", 1);
		parameters.put("offset", 2L);
		List<Map<String, Object>> rows = session.selectList(NS + "page", parameters);

		assertEquals("GOOD_FEED", rows.get(0).get("source_kind"));
	}

	@Test
	void reversedOriginalRemainsVisibleAndMissingGoodNeverUsesNgReport() {
		db.update("UPDATE mom_mes.mes_report_material_consume SET status='REVERSED',reverse_request_id='REVERSE-1' WHERE id=11");
		db.update("DELETE FROM mom_mes.mes_task_report WHERE id=101");
		var row = rows(Map.of("sourceKind", "GOOD_FEED")).get(0);

		assertEquals("REVERSED", row.get("source_status"));
		assertEquals("2.00000001", row.get("quantity"));
		assertNull(row.get("report_found"));
		assertEquals("GOOD", row.get("report_domain"));
	}

	@Test
	void exactDetailDoesNotExposeOtherTaskOrOrganization() {
		var p = parameters(Map.of());
		p.put("kind", "GOOD_FEED");
		p.put("id", 11L);
		assertNotNull(session.selectOne(NS + "original", p));
		p.put("q", Map.of("taskCode", "T10"));
		assertNull(session.selectOne(NS + "original", p));
		p.put("q", Map.of("taskCode", "T1"));
		p.put("factory", "F2");
		assertNull(session.selectOne(NS + "original", p));
	}

	@Test
	void authenticatedPageKeepsDecimalAndIdTextAndDefaultsPagination() {
		actor();
		var page = service().page(new Query().setTaskCode(" T1 "));

		assertEquals("MES_MATERIAL_TRACE_V1", page.getContractVersion());
		assertEquals("1", page.getTenantId());
		assertEquals("F1", page.getFactoryCode());
		assertEquals(1, page.getCurrent());
		assertEquals(20, page.getSize());
		assertEquals(4L, page.getTotal());
		assertEquals("COMBINED_FEED:81", page.getRecords().get(0).getKey());
		assertEquals("9.12345678", page.getRecords().get(0).getQuantity());
		assertEquals("1.75", page.getRecords().get(1).getRequirementQuantity());
		assertNull(page.getRecords().get(0).getReportId());
	}

	@Test
	void serviceRejectsMissingIdentityScopeUnknownKindAndInvalidPagination() {
		assertThrows(CheckedException.class, () -> service().page(new Query().setTaskCode("T1")));
		actor();
		assertThrows(CheckedException.class, () -> service().page(new Query()));
		assertThrows(CheckedException.class, () -> service().page(new Query().setTaskCode("T1").setSize(101)));
		assertThrows(CheckedException.class, () -> service().page(new Query().setTaskCode("T1").setCurrent(0)));
		assertThrows(CheckedException.class, () -> service().detail("NG_BACKFLUSH", "11", "T1", null));
		assertThrows(CheckedException.class, () -> service().detail("GOOD_FEED", "90071992547409929999", "T1", null));
		FactoryContextHolder.setFactorySkip();
		assertThrows(CheckedException.class, () -> service().page(new Query().setTaskCode("T1")));
	}

	@Test
	void combinedDetailSeparatesOutputSharesAndPartialRestoresFromOriginalConsumption() {
		actor();
		db.update("INSERT INTO mom_mes.mes_production_feed_reversal VALUES(111,1,'F1',71,'PARTIAL-1','MATERIAL_RESTORED')");
		db.update("INSERT INTO mom_mes.mes_production_feed_restore_line VALUES(112,1,'F1',71,81,21,111,4.12345678,'kg')");
		db.update("INSERT INTO mom_mes.mes_production_feed_reversal VALUES(113,2,'F1',71,'FOREIGN','MATERIAL_RESTORED')");
		db.update("INSERT INTO mom_mes.mes_production_feed_restore_line VALUES(114,2,'F1',71,81,21,113,99,'kg')");
		var detail = service().detail("COMBINED_FEED", "81", "T1", "A1");

		assertEquals("9.12345678", detail.getRecord().getQuantity());
		assertEquals("HAS_RESTORATION", detail.getRecord().getRestorationStatus());
		assertEquals(2, detail.getAttributions().size());
		assertEquals("GOOD", detail.getAttributions().get(0).getFields().get("output_kind"));
		assertEquals("NG", detail.getAttributions().get(1).getFields().get("output_kind"));
		assertEquals(1, detail.getRestores().size());
		assertEquals("4.12345678", detail.getRestores().get(0).getFields().get("restored_num"));
		assertEquals(1, detail.getReversals().size());
		assertEquals("PARTIAL-1", detail.getReversals().get(0).getFields().get("request_id"));
		assertThrows(CheckedException.class, () -> service().detail("COMBINED_FEED", "81", "T10", "A1"));
	}

	@Test
	void missingGoodRootAndUnknownUnitsStayExplicitInsteadOfBorrowingNgEvidence() {
		actor();
		db.update("DELETE FROM mom_mes.mes_task_report WHERE id=101");
		db.update("DELETE FROM mom_mes.mes_warehouse_feed WHERE id=21");
		var detail = service().detail("GOOD_FEED", "11", "T1", "A1");

		assertNull(detail.getOriginalRoot());
		assertNull(detail.getRecord().getUnit());
		assertFalse(detail.getRecord().getDataGaps().isEmpty());
		assertEquals(1, detail.getRelatedSources().size());
		assertEquals("mes_report_material_consume", detail.getRelatedSources().get(0).getTableName());
	}

	@Test
	void partialGoodCorrectionHasExactOriginalSourceLinkAndDoesNotChangeOriginalQuantity() {
		actor();
		db.update("INSERT INTO mom_mes.mes_report_reverse_request VALUES(121,1,'F1',101,'T1','P1','A1','APPROVED')");
		db.update("INSERT INTO mom_mes.mes_report_quantity_correction_line VALUES(122,1,'F1',121,'PARTIAL-GOOD',101,'MATERIAL_CONSUME',11,0.5,'piece')");
		db.update("INSERT INTO mom_mes.mes_report_quantity_correction_line VALUES(123,1,'F1',121,'OTHER-SOURCE',101,'MATERIAL_CONSUME',999,8,'piece')");
		var detail = service().detail("GOOD_FEED", "11", "T1", "A1");

		assertEquals("2.00000001", detail.getRecord().getQuantity());
		assertEquals("EFFECTIVE", detail.getRecord().getSourceStatus());
		assertEquals("HAS_RESTORATION", detail.getRecord().getRestorationStatus());
		assertEquals(1, detail.getRestores().size());
		assertEquals("mes_report_quantity_correction_line", detail.getRestores().get(0).getTableName());
		assertEquals("0.50000000", detail.getRestores().get(0).getFields().get("correction_quantity"));
		assertEquals(1, detail.getReversals().size());
		assertEquals("121", detail.getReversals().get(0).getSourceId());
		assertEquals("mes_report_reverse_request", detail.getReversals().get(0).getTableName());
		assertEquals("mes_task_report", detail.getOriginalRoot().getTableName());
	}

	@Test
	void ngDetailUsesOnlyItsOriginalOperationAndRestores() {
		actor();
		db.update("INSERT INTO mom_mes.mes_production_material_reversal VALUES(131,1,'F1',51,101,'NG-RESTORE','MATERIAL_RESTORED')");
		db.update("INSERT INTO mom_mes.mes_production_material_restore VALUES(132,1,'F1',51,21,131,4.5,'kg')");
		db.update("INSERT INTO mom_mes.mes_production_material_restore VALUES(133,1,'F1',999,21,131,99,'kg')");
		var detail = service().detail("NG_FEED", "61", "T1", "A1");

		assertEquals("NG", detail.getRecord().getReportDomain());
		assertEquals("mes_production_material_operation", detail.getOriginalRoot().getTableName());
		assertEquals("NG-1", detail.getOriginalRoot().getFields().get("request_id"));
		assertEquals(1, detail.getRestores().size());
		assertEquals("4.50000000", detail.getRestores().get(0).getFields().get("restored_num"));
		assertEquals(1, detail.getReversals().size());
	}

	@Test
	void foreignSameIdFeedCannotFillHistoricalUnitAndFactorySwitchCannotLeakRows() {
		actor();
		db.update("DELETE FROM mom_mes.mes_warehouse_feed WHERE id=21");
		db.update("INSERT INTO mom_mes.mes_warehouse_feed VALUES(21,2,'F1','T1','A1','M1','FOREIGN-UNIT','B1','RAW-1')");
		var detail = service().detail("GOOD_FEED", "11", "T1", "A1");

		assertNull(detail.getRecord().getUnit());
		assertEquals(1, detail.getRelatedSources().size());
		FactoryContextHolder.setFactoryCode("F2");
		assertEquals(0L, service().page(new Query().setTaskCode("T1")).getTotal());
		assertThrows(CheckedException.class, () -> service().detail("GOOD_FEED", "11", "T1", "A1"));
	}

	@Test
	void largeIdsAndNonTerminatingBackflushRequirementAreNeverRounded() {
		actor();
		db.update("UPDATE mom_mes.mes_report_material_consume SET id=9007199254740993 WHERE id=11");
		db.update("UPDATE mom_mes.mes_warehouse_inout SET operate_num=1,substitute_ratio=3 WHERE id=31");
		var manual = service().detail("GOOD_FEED", "9007199254740993", "T1", null);
		var backflush = service().detail("GOOD_BACKFLUSH", "31", "T1", null);

		assertEquals("9007199254740993", manual.getRecord().getSourceId());
		assertNull(backflush.getRecord().getRequirementQuantity());
		assertFalse(backflush.getRecord().getDataGaps().isEmpty());
	}

	private MesMaterialConsumptionTraceService service() {
		return new MesMaterialConsumptionTraceService(MesFactoryAccessTestSupport.authorized(),
				session.getMapper(MesMaterialConsumptionTraceMapper.class));
	}

	@Test
	void allDetailKindsBindNumericForeignKeysAsLongForPostgresql() {
		actor();
		var delegate = session.getMapper(MesMaterialConsumptionTraceMapper.class);
		var checked = (MesMaterialConsumptionTraceMapper) java.lang.reflect.Proxy.newProxyInstance(
				MesMaterialConsumptionTraceMapper.class.getClassLoader(),
				new Class<?>[] { MesMaterialConsumptionTraceMapper.class }, (proxy, method, arguments) -> {
					if (arguments != null && arguments.length == 1 && arguments[0] instanceof Map<?, ?> scope) {
						for (String key : List.of("id", "rootId", "feedId", "stockId", "reportId")) {
							if (scope.get(key) != null) {
								assertInstanceOf(Long.class, scope.get(key), key + " must bind as BIGINT rather than VARCHAR");
							}
						}
					}
					return method.invoke(delegate, arguments);
				});
		var service = new MesMaterialConsumptionTraceService(MesFactoryAccessTestSupport.authorized(), checked);

		for (var source : Map.of("GOOD_FEED", "11", "GOOD_BACKFLUSH", "31", "NG_FEED", "61", "COMBINED_FEED", "81").entrySet()) {
			assertNotNull(service.detail(source.getKey(), source.getValue(), "T1", "A1").getOriginalRoot());
		}
	}

	private void actor() {
		var user = new MomUser(7L, "trace-reader", 1L, null, null, null, "Trace reader", null,
				1L, "unused", true, true, "USER", true, true, List.of());
		SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, "", List.of()));
		TenantContextHolder.setTenantId(1L);
		FactoryContextHolder.setFactoryCode("F1");
	}

	private List<Map<String, Object>> rows(Map<String, Object> filters) {
		return session.selectList(NS + "page", parameters(filters));
	}

	private long total(Map<String, Object> filters) {
		return session.selectOne(NS + "total", parameters(filters));
	}

	private Map<String, Object> parameters(Map<String, Object> filters) {
		var q = new HashMap<>(filters);
		q.put("taskCode", "T1");
		return new HashMap<>(Map.of("tenant", 1L, "factory", "F1", "q", q, "limit", 20, "offset", 0L));
	}

	private void fixture() {
		String organization = "id bigint,tenant_id bigint,factory_code varchar,";
		db.execute("CREATE TABLE mom_mes.mes_task_arrange(" + organization + "code varchar,task_code varchar,produce_task_code varchar)");
		db.execute("CREATE TABLE mom_mes.mes_task_report(" + organization + "task_code varchar,produce_task_code varchar,arrange_code varchar,request_id varchar,status varchar,reverse_request_id varchar,reverse_time timestamp)");
		db.execute("CREATE TABLE mom_mes.mes_task_report_unqualified(" + organization + "task_code varchar,produce_task_code varchar,arrange_code varchar,request_id varchar)");
		db.execute("CREATE TABLE mom_mes.mes_warehouse_feed(" + organization + "task_code varchar,arrange_code varchar,mat_code varchar,mat_unit varchar,mat_batch varchar,mat_barcode varchar)");
		db.execute("CREATE TABLE mom_mes.mes_report_material_consume(" + organization + "report_id bigint,report_request_id varchar,arrange_code varchar,feed_id bigint,requirement_mat_code varchar,mat_code varchar,mat_batch varchar,mat_barcode varchar,consume_num numeric(28,8),requirement_num numeric(28,8),substitute_ratio numeric(28,8),status varchar,reverse_request_id varchar,reverse_time timestamp,create_time timestamp)");
		db.execute("CREATE TABLE mom_mes.mes_warehouse_inout(" + organization + "bill_id bigint,bill_no varchar,produce_task_code varchar,resource_no varchar,source_stock_id bigint,requirement_mat_code varchar,mat_code varchar,mat_batch varchar,mat_barcode varchar,mat_unit varchar,operate_num numeric(28,8),substitute_ratio numeric(28,8),biz_type varchar,operate_type varchar,create_time timestamp)");
		db.execute("CREATE TABLE mom_mes.mes_production_material_operation(" + organization + "task_code varchar,produce_task_code varchar,arrange_code varchar,request_id varchar,source_unqualified_report_id bigint,status varchar)");
		db.execute("CREATE TABLE mom_mes.mes_production_material_allocation(" + organization + "operation_id bigint,source_unqualified_report_id bigint,feed_id bigint,requirement_mat_code varchar,requirement_unit varchar,mat_code varchar,mat_unit varchar,consume_num numeric(28,8),requirement_num numeric(28,8),create_time timestamp)");
		db.execute("CREATE TABLE mom_mes.mes_production_feed_batch(" + organization + "task_code varchar,produce_task_code varchar,arrange_code varchar,request_id varchar,status varchar)");
		db.execute("CREATE TABLE mom_mes.mes_production_feed_consumption(" + organization + "batch_id bigint,feed_id bigint,requirement_mat_code varchar,requirement_unit varchar,mat_code varchar,mat_unit varchar,consume_num numeric(28,8),requirement_num numeric(28,8),create_time timestamp)");
		db.execute("CREATE TABLE mom_mes.mes_production_feed_share(" + organization + "batch_id bigint,consumption_id bigint,output_id bigint,consume_num numeric(28,8),requirement_num numeric(28,8))");
		db.execute("CREATE TABLE mom_mes.mes_production_feed_restore_line(" + organization + "batch_id bigint,consumption_id bigint,feed_id bigint,reversal_id bigint,restored_num numeric(28,8),mat_unit varchar)");
		db.execute("CREATE TABLE mom_mes.mes_production_feed_output(" + organization + "batch_id bigint,output_kind varchar,good_report_id bigint,ng_report_id bigint,quantity numeric(28,8))");
		db.execute("CREATE TABLE mom_mes.mes_production_feed_reversal(" + organization + "batch_id bigint,request_id varchar,status varchar)");
		db.execute("CREATE TABLE mom_mes.mes_production_material_reversal(" + organization + "operation_id bigint,source_unqualified_report_id bigint,request_id varchar,status varchar)");
		db.execute("CREATE TABLE mom_mes.mes_production_material_restore(" + organization + "operation_id bigint,feed_id bigint,reversal_id bigint,restored_num numeric(28,8),mat_unit varchar)");
		db.execute("CREATE TABLE mom_mes.mes_warehouse_live_stock(" + organization + "mat_code varchar)");
		db.execute("CREATE TABLE mom_mes.mes_report_quantity_correction_line(" + organization + "reverse_request_id bigint,operation_request_id varchar,report_id bigint,allocation_type varchar,source_id bigint,correction_quantity numeric(28,8),quantity_unit varchar)");
		db.execute("CREATE TABLE mom_mes.mes_report_reverse_request(" + organization + "report_id bigint,task_code varchar,produce_task_code varchar,arrange_code varchar,status varchar)");
		db.update("INSERT INTO mom_mes.mes_task_arrange VALUES(1,1,'F1','A1','T1','P1')");
		db.update("INSERT INTO mom_mes.mes_task_report VALUES(101,1,'F1','T1','P1','A1','GOOD-1','EFFECTIVE',NULL,NULL)");
		db.update("INSERT INTO mom_mes.mes_task_report_unqualified VALUES(101,1,'F1','T1','P1','A1','NG-DOMAIN')");
		db.update("INSERT INTO mom_mes.mes_warehouse_feed VALUES(21,1,'F1','T1','A1','M1','kg','B1','RAW-1')");
		db.update("INSERT INTO mom_mes.mes_report_material_consume VALUES(11,1,'F1',101,'GOOD-1','A1',21,'REQ1','M1','B1','RAW-1',2.00000001,1.00000001,1,'EFFECTIVE',NULL,NULL,'2026-10-23 08:00:00')");
		db.update("INSERT INTO mom_mes.mes_warehouse_inout VALUES(31,1,'F1',101,'A1','P1','GOOD-1',41,'REQ2','M2','B2','RAW-2','m',3.5,2,'back_flush_out','out','2026-10-23 08:00:00')");
		db.update("INSERT INTO mom_mes.mes_production_material_operation VALUES(51,1,'F1','T1','P1','A1','NG-1',101,'MATERIAL_RECORDED')");
		db.update("INSERT INTO mom_mes.mes_production_material_allocation VALUES(61,1,'F1',51,101,21,'REQ1','kg','M1','kg',4.5,4.5,'2026-10-23 08:00:00')");
		db.update("INSERT INTO mom_mes.mes_production_feed_batch VALUES(71,1,'F1','T1','P1','A1','BOTH-1','MATERIAL_RECORDED')");
		db.update("INSERT INTO mom_mes.mes_production_feed_consumption VALUES(81,1,'F1',71,21,'REQ1','kg','M1','kg',9.12345678,9.12345678,'2026-10-23 08:00:00')");
		db.update("INSERT INTO mom_mes.mes_production_feed_output VALUES(91,1,'F1',71,'GOOD',101,NULL,1),(92,1,'F1',71,'NG',NULL,101,1)");
		db.update("INSERT INTO mom_mes.mes_production_feed_share VALUES(93,1,'F1',71,81,91,4.12345678,4.12345678),(94,1,'F1',71,81,92,5,5)");
	}
}
