package com.kitex.mom.mes.service.impl;

import com.kitex.mom.mes.dto.MesProductionTimeDTO.*;
import com.kitex.mom.mes.mapper.MesProductionTimeMapper;
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

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 使用真实Mapper XML和H2执行SQL；不模拟来源筛选，不替代目标PG及组织拦截器联验。 */
class MesProductionTimeMapperTest {

	private JdbcTemplate db;
	private SqlSession session;
	private MesProductionTimeMapper mapper;

	@BeforeEach
	void setup() throws Exception {
		var source = new DriverManagerDataSource("jdbc:h2:mem:production-time-" + UUID.randomUUID()
				+ ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
		source.setDriverClassName("org.h2.Driver");
		db = new JdbcTemplate(source);
		db.execute("CREATE SCHEMA mom_mes");
		db.execute("CREATE TABLE mom_mes.mes_task_arrange(id bigint primary key,code varchar,task_code varchar,"
				+ "produce_task_code varchar,tenant_id bigint,factory_code varchar,del_flag varchar,"
				+ "workshop_code varchar,shift_code varchar,planned_device_code varchar)");
		db.execute("CREATE TABLE mom_mes.mes_task_execution_segment(id bigint primary key,arrange_id bigint,"
				+ "arrange_code varchar,task_code varchar,produce_task_code varchar,tenant_id bigint,factory_code varchar,"
				+ "del_flag varchar,device_code varchar,segment_type varchar,start_time timestamp,end_time timestamp,"
				+ "status varchar,sample_eligible boolean,close_reason varchar,exclusion_reason varchar)");
		db.execute("CREATE TABLE mom_mes.mes_labor_entry(id bigint primary key,arrange_id bigint,arrange_code varchar,"
				+ "task_code varchar,produce_task_code varchar,tenant_id bigint,factory_code varchar,del_flag varchar,"
				+ "worker_code varchar,labor_start timestamp,labor_end timestamp,status varchar,reason varchar)");
		db.execute("CREATE TABLE mom_mes.mes_arrange_timing_event(id bigint primary key,arrange_id bigint,"
				+ "arrange_code varchar,task_code varchar,produce_task_code varchar,tenant_id bigint,factory_code varchar,"
				+ "del_flag varchar,event_time timestamp,event_version int,previous_status varchar,status varchar,"
				+ "proven boolean,event_kind varchar,segment_id bigint,reason varchar)");
		db.execute("CREATE TABLE mom_mes.mes_workpiece_time_interval(id bigint primary key,arrange_id bigint,"
				+ "arrange_code varchar,task_code varchar,produce_task_code varchar,tenant_id bigint,factory_code varchar,"
				+ "del_flag varchar,device_code varchar,worker_code varchar,identifier varchar,identifier_type varchar,"
				+ "kind varchar,start_time timestamp,end_time timestamp,status varchar,reason_code varchar)");
		db.update("INSERT INTO mom_mes.mes_task_arrange VALUES(1,'A1','T1','P1',1,'F1','0','W1','S1','D1')");
		db.update("INSERT INTO mom_mes.mes_task_arrange VALUES(2,'A2','T1','P1',2,'F1','0','W1','S1','D1')");
		db.update("INSERT INTO mom_mes.mes_task_arrange VALUES(3,'A3','T1','P1',1,'F2','0','W1','S1','D1')");
		db.execute("ALTER TABLE mom_mes.mes_task_arrange ADD COLUMN status varchar DEFAULT 'PRODUCT'");
		Configuration configuration = new Configuration();
		configuration.setMapUnderscoreToCamelCase(true);
		configuration.setEnvironment(new Environment("production-time", new JdbcTransactionFactory(), source));
		try (var xml = getClass().getResourceAsStream("/mapper/MesProductionTimeMapper.xml")) {
			assertNotNull(xml);
			new XMLMapperBuilder(xml, configuration, "MesProductionTimeMapper.xml", configuration.getSqlFragments()).parse();
		}
		session = new SqlSessionFactoryBuilder().build(configuration).openSession(true);
		mapper = session.getMapper(MesProductionTimeMapper.class);
	}

	@AfterEach
	void close() {
		if (session != null) {
			session.close();
		}
	}

	@Test
	void actualSqlExcludesOtherOrganizationsAndPreservesLongSourceIds() {
		segment(9007199254740993L, 1, 1, "F1", "RUN");
		segment(2, 2, 2, "F1", "RUN");
		segment(3, 3, 1, "F2", "RUN");
		var rows = mapper.segments(1L, "F1", query(), 100);

		assertEquals(1, rows.size());
		assertEquals("9007199254740993", rows.get(0).getSourceId());
		assertEquals("W1", rows.get(0).getWorkshopCode());
		assertTrue(mapper.segments(1L, "F1", query().setShiftCode("OTHER"), 100).isEmpty());
	}

	@Test
	void setupSegmentCannotMasqueradeAsActualRunTime() {
		segment(1, 1, 1, "F1", "SETUP");
		var result = new MesProductionTimeCalculator().calculate(query(), mapper.segments(1L, "F1", query(), 100), observed());

		assertNull(result.getTotals().getProcessingSeconds());
		assertEquals(1, result.getTotals().getInvalidSourceCount());
	}

	@Test
	void onlyEffectiveApprovedLaborContributes() {
		for (String status : new String[]{"APPROVED", "VOID_PENDING", "PENDING", "VOIDED"}) {
			db.update("INSERT INTO mom_mes.mes_labor_entry VALUES(?,1,'A1','T1','P1',1,'F1','0',?,'2026-10-22 08:00:00',"
					+ "'2026-10-22 09:00:00',?,'实际作业')", status.hashCode(), status, status);
		}
		var rows = mapper.labor(1L, "F1", query(), 100);

		assertEquals(2, rows.size());
		assertEquals("7200", new MesProductionTimeCalculator().calculate(query(), rows, observed()).getTotals().getLaborSeconds());
	}

	@Test
	void pauseStartingBeforeWindowUsesRealConsecutiveResume() {
		event(1, 1, "PRODUCT", "PAUSE", "2026-10-22 07:30:00", true);
		event(2, 2, "PAUSE", "PRODUCT", "2026-10-22 08:30:00", true);
		var rows = mapper.pauses(1L, "F1", query(), 100);

		assertEquals(1, rows.size());
		assertEquals("1800", new MesProductionTimeCalculator().calculate(query(), rows, observed()).getTotals().getPausedSeconds());
	}

	@Test
	void missingEventVersionIsReportedAsUnknownInsteadOfProvenPause() {
		event(1, 1, "PRODUCT", "PAUSE", "2026-10-22 08:00:00", true);
		event(2, 3, "PAUSE", "PRODUCT", "2026-10-22 09:00:00", true);
		var result = new MesProductionTimeCalculator().calculate(query(), mapper.pauses(1L, "F1", query(), 100), observed());

		assertNull(result.getTotals().getPausedSeconds());
		assertEquals(1, result.getTotals().getInvalidSourceCount());
	}

	@Test
	void missingResumeEventCannotCountPauseThroughAnAlreadyRunningArrangement() {
		event(1, 1, "PRODUCT", "PAUSE", "2026-10-22 08:00:00", true);
		var result = new MesProductionTimeCalculator().calculate(query(), mapper.pauses(1L, "F1", query(), 100), observed());

		assertNull(result.getTotals().getPausedSeconds());
		assertEquals(1, result.getTotals().getInvalidSourceCount());
	}

	@Test
	void unknownWorkpieceKindMustNotBecomePausedTime() {
		db.update("INSERT INTO mom_mes.mes_workpiece_time_interval VALUES(1,1,'A1','T1','P1',1,'F1','0',"
				+ "'D1','U1','SN1','SN','UNKNOWN','2026-10-22 08:00:00','2026-10-22 09:00:00','CLOSED',NULL)");
		var result = new MesProductionTimeCalculator().calculate(query(), mapper.workpieces(1L, "F1", query(), 100), observed());

		assertNull(result.getTotals().getWorkpiecePausedSeconds());
		assertEquals(1, result.getTotals().getInvalidSourceCount());
	}

	@Test
	void orphanCountUsesSourceOrganizationAndExactOriginalArrangement() {
		segment(1, 1, 1, "F1", "RUN");
		segment(2, 99, 1, "F1", "RUN");
		segment(3, 99, 2, "F1", "RUN");

		assertEquals(1, mapper.orphanedSources(1L, "F1", query()));
		assertEquals(0, mapper.orphanedSources(1L, "F1", query().setTaskCode("OTHER")));
	}

	@Test
	void historicalZeroIntervalsOutsideWindowDoNotConsumeTheSourceLimit() {
		segment(1, 1, 1, "F1", "RUN");
		segment(2, 99, 1, "F1", "RUN");
		db.update("UPDATE mom_mes.mes_task_execution_segment SET start_time='2026-10-22 07:00:00',end_time='2026-10-22 07:00:00'");
		db.update("INSERT INTO mom_mes.mes_workpiece_time_interval VALUES(1,1,'A1','T1','P1',1,'F1','0',"
				+ "'D1','U1','SN1','SN','RUN','2026-10-22 07:00:00','2026-10-22 07:00:00','CLOSED',NULL)");
		event(1, 1, "PRODUCT", "PAUSE", "2026-10-22 07:00:00", true);
		event(2, 2, "PAUSE", "PRODUCT", "2026-10-22 07:00:00", true);

		assertAll(() -> assertTrue(mapper.segments(1L, "F1", query(), 1).isEmpty()),
				() -> assertTrue(mapper.workpieces(1L, "F1", query(), 1).isEmpty()),
				() -> assertTrue(mapper.pauses(1L, "F1", query(), 1).isEmpty()),
				() -> assertEquals(0, mapper.orphanedSources(1L, "F1", query())));
	}

	@Test
	void actualZeroWorkpieceIntervalAtWindowStartIsNotLostBySql() {
		db.update("INSERT INTO mom_mes.mes_workpiece_time_interval VALUES(1,1,'A1','T1','P1',1,'F1','0',"
				+ "'D1','U1','SN1','SN','RUN','2026-10-22 08:00:00','2026-10-22 08:00:00','CLOSED',NULL)");
		var rows = mapper.workpieces(1L, "F1", query(), 100);

		assertEquals(1, rows.size());
		assertEquals("0", new MesProductionTimeCalculator().calculate(query(), rows, observed()).getTotals().getWorkpieceProcessingSeconds());
	}

	@Test
	void exactWorkpieceScopeSeparatesTaskTypeOrganizationAndNearMatches() {
		workpiece(101, 1, "A1", "T1", 1, "F1", "SN", "SN1");
		workpiece(102, 1, "A1", "T1", 1, "F1", "BATCH", "SN1");
		workpiece(103, 1, "A1", "T1", 1, "F1", "SN", "sn1");
		workpiece(104, 1, "A1", "T1", 1, "F1", "SN", " SN1 ");
		db.update("INSERT INTO mom_mes.mes_task_arrange VALUES(4,'A4','T2','P1',1,'F1','0','W1','S1','D1','PRODUCT')");
		workpiece(105, 4, "A4", "T2", 1, "F1", "SN", "SN1");
		workpiece(106, 2, "A2", "T1", 2, "F1", "SN", "SN1");
		workpiece(107, 3, "A3", "T1", 1, "F2", "SN", "SN1");
		workpiece(108, 1, "A1", "T1", 1, "F1", " SN ", "SN1");
		assertEquals(java.util.List.of("108"), mapper.workpieces(1L, "F1", identifierQuery(" SN ", "SN1"), 100)
				.stream().map(Fact::getSourceId).toList());
		var q = identifierQuery("SN", "SN1");
		var rows = mapper.workpieces(1L, "F1", q, 1);
		assertEquals(java.util.List.of("101"), rows.stream().map(Fact::getSourceId).toList());
		assertEquals(java.util.List.of("104"), mapper.workpieces(1L, "F1", identifierQuery("SN", " SN1 "), 100)
				.stream().map(Fact::getSourceId).toList());
	}

	@Test
	void exactIdentifierScopeExcludesUnrelatedOrphansAndOtherTimeKinds() {
		segment(1, 99, 1, "F1", "RUN");
		segment(2, 1, 1, "F1", "RUN");
		workpiece(101, 99, "A99", "T1", 1, "F1", "SN", "SN1");
		workpiece(102, 99, "A99", "T1", 1, "F1", "BATCH", "SN1");
		workpiece(103, 99, "A99", "T1", 1, "F1", "SN", "OTHER");
		workpiece(104, 99, "A99", "T1", 2, "F1", "SN", "SN1");
		var q = identifierQuery("SN", "SN1");
		assertEquals(1, mapper.orphanedSources(1L, "F1", q));
		assertTrue(mapper.segments(1L, "F1", q, 100).isEmpty());
		assertTrue(mapper.labor(1L, "F1", q, 100).isEmpty());
		assertTrue(mapper.pauses(1L, "F1", q, 100).isEmpty());
	}

	@Test
	void identifierFilteringPrecedesPaginationAndFullScopeSummarizing() {
		for (long id = 1; id <= 55; id++) {
			workpiece(id, 1, "A1", "T1", 1, "F1", "SN", "SN1");
			workpiece(100 + id, 1, "A1", "T1", 1, "F1", "SN", "OTHER");
		}
		var q = identifierQuery("SN", "SN1").setDetailPage(2).setDetailSize(50).setGroupBy("WORKPIECE");
		var result = new MesProductionTimeCalculator().calculate(q, mapper.workpieces(1L, "F1", q, 20001), observed());
		assertEquals(55, result.getDetailTotal());
		assertEquals(5, result.getDetails().size());
		assertEquals(1, result.getGroups().size());
		assertEquals("SN1", result.getGroups().get(0).getIdentifier());
		// Overlapping recordings for the same resource are unioned, not multiplied by row count.
		assertEquals("3600", result.getTotals().getWorkpieceProcessingSeconds());
		assertNull(result.getTotals().getProcessingSeconds());
	}

	private Query identifierQuery(String type, String identifier) {
		return assertDoesNotThrow(() -> new com.fasterxml.jackson.databind.ObjectMapper().updateValue(query().setTaskCode("T1"),
				java.util.Map.of("identifierType", type, "identifier", identifier)));
	}

	private void workpiece(long id, long arrange, String arrangeCode, String task, long tenant, String factory, String type, String identifier) {
		db.update("INSERT INTO mom_mes.mes_workpiece_time_interval VALUES(?,?,?,?, 'P1',?,?,'0',"
				+ "'D1','U1',?,?,'RUN','2026-10-22 08:00:00','2026-10-22 09:00:00','CLOSED',NULL)",
				id, arrange, arrangeCode, task, tenant, factory, identifier, type);
	}

	private void segment(long id, long arrange, long tenant, String factory, String type) {
		db.update("INSERT INTO mom_mes.mes_task_execution_segment VALUES(?,?,'A'||?,'T1','P1',?,?,'0','D1',?,"
				+ "'2026-10-22 08:00:00','2026-10-22 09:00:00','CLOSED',TRUE,'FINISH',NULL)", id, arrange, arrange, tenant, factory, type);
	}

	private void event(long id, int version, String before, String state, String time, boolean proven) {
		db.update("INSERT INTO mom_mes.mes_arrange_timing_event VALUES(?,1,'A1','T1','P1',1,'F1','0',?,? ,?,?,?,'STATE_CHANGE',NULL,'ANDON_PAUSE')",
				id, LocalDateTime.parse(time.replace(' ', 'T')), version, before, state, proven);
	}

	private Query query() {
		return new Query().setStartTime(LocalDateTime.parse("2026-10-22T08:00:00"))
				.setEndTime(LocalDateTime.parse("2026-10-22T10:00:00"));
	}

	private LocalDateTime observed() {
		return LocalDateTime.parse("2026-10-23T00:00:00");
	}
}
