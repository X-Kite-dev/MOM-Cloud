package com.kitex.mom.mes.service.impl;

import com.kitex.mom.mes.dto.MesWipAgingDTO.*;
import com.kitex.mom.mes.mapper.MesWipAgingMapper;
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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 真实XML/H2覆盖当前工序队列，不能代替PG及HTTP组织链路联验。 */
class MesWipAgingMapperTest {

	private JdbcTemplate db;
	private SqlSession session;
	private MesWipAgingMapper mapper;
	private final LocalDateTime observed = LocalDateTime.parse("2026-10-23T12:00:00.123456");
	private final MesWipAgingCalculator calculator = new MesWipAgingCalculator();

	@BeforeEach
	void setup() throws Exception {
		var source = new DriverManagerDataSource("jdbc:h2:mem:wip-aging-" + UUID.randomUUID()
				+ ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
		source.setDriverClassName("org.h2.Driver");
		db = new JdbcTemplate(source);
		db.execute("CREATE SCHEMA mom_mes");
		db.execute("CREATE TABLE mom_mes.mes_task(id bigint primary key,code varchar,tenant_id bigint,"
				+ "factory_code varchar,del_flag varchar)");
		db.execute("CREATE TABLE mom_mes.mes_produce_task(id bigint primary key,code varchar,task_code varchar,"
				+ "tenant_id bigint,factory_code varchar,del_flag varchar,workshop_code varchar,route_code varchar,"
				+ "source_route_node_code varchar,mat_code varchar,status varchar,notice_date timestamp,"
				+ "start_time timestamp,finish_time timestamp)");
		db.update("INSERT INTO mom_mes.mes_task VALUES(100,'PARENT',1,'F1','0')");
		Configuration configuration = new Configuration();
		configuration.setMapUnderscoreToCamelCase(true);
		configuration.setEnvironment(new Environment("wip-aging", new JdbcTransactionFactory(), source));
		try (var xml = getClass().getResourceAsStream("/mapper/MesWipAgingMapper.xml")) {
			assertNotNull(xml);
			new XMLMapperBuilder(xml, configuration, "MesWipAgingMapper.xml", configuration.getSqlFragments()).parse();
		}
		session = new SqlSessionFactoryBuilder().build(configuration).openSession(true);
		mapper = session.getMapper(MesWipAgingMapper.class);
	}

	@AfterEach
	void close() {
		if (session != null) {
			session.close();
		}
	}

	@Test
	void allFiveStatesAndUnknownRemainWhileTerminalsAreExcluded() {
		String[] states = {"WAIT", "UN_RECEIVE", "RECEIVE", "PRODUCT", "PAUSE", "ALIEN", null, "FINISH", "CLOSE"};
		for (int i = 0; i < states.length; i++) {
			process(i + 1, states[i], observed.minusHours(25), null);
		}
		var s = summary(new Query());

		assertEquals("7", s.getPopulationCount());
		assertEquals("1", s.getWaitingDispatchCount());
		assertEquals("1", s.getWaitingReceiveCount());
		assertEquals("1", s.getReceivedCount());
		assertEquals("1", s.getProducingCount());
		assertEquals("1", s.getPausedCount());
		assertEquals("2", s.getUnknownStatusCount());
		assertEquals("5", s.getKnownQueueAgeCount());
		assertEquals("2", s.getUnknownQueueAgeCount());
		assertEquals("2", mapper.detailTotal(1L, "F1", new Query().setStatus("UNKNOWN"), observed, cutoff()));
		assertEquals("INCOMPLETE", s.getOverThresholdRate().status());
	}

	@Test
	void paddedRawStatusRemainsUnknownRatherThanBeingPromotedToHealthy() {
		process(1, " WAIT ", observed.minusDays(2), null);

		assertEquals("1", summary(new Query()).getUnknownStatusCount());
		assertEquals("0", summary(new Query()).getKnownQueueAgeCount());
		assertEquals(" WAIT ", rows(new Query().setStatus("UNKNOWN")).get(0).getStatus());
	}

	@Test
	void thresholdIsStrictAndMicrosecondsUseExactNaturalElapsed() {
		process(9007199254740993L, "PAUSE", observed.minusHours(24).minusNanos(1000), observed.minusHours(2));
		process(2, "WAIT", observed.minusHours(24), null);
		process(3, "RECEIVE", observed.minusHours(24).plusNanos(1000), null);
		var s = summary(new Query());
		var row = rows(new Query().setAgingCategory("OVER")).get(0);

		assertEquals("1", s.getOverThresholdCount());
		assertEquals("33.3333", s.getOverThresholdRate().percentage());
		assertEquals("86400.000001", s.getMaxQueueResidenceSeconds());
		assertEquals("9007199254740993", row.getObjectId());
		assertEquals("86400.000001", row.getQueueResidence().seconds());
		assertEquals("7200", row.getSinceFirstStart().seconds());
		assertEquals("2", mapper.detailTotal(1L, "F1", new Query().setAgingCategory("WITHIN"), observed, cutoff()));
	}

	@Test
	void independentStartSurvivesMissingNoticeButWaitingWithoutStartHasNoStartMetric() {
		process(1, "PRODUCT", null, observed.minusHours(2));
		process(2, "WAIT", observed.minusHours(1), null);
		process(3, "PAUSE", observed.minusHours(1), null);
		process(4, "WAIT", null, null);
		process(5, "UN_RECEIVE", observed.plusHours(1), null);
		process(6, "RECEIVE", observed.minusHours(3), observed.minusHours(2));
		var rows = rows(new Query());
		var missingNotice = rows.stream().filter(r -> "1".equals(r.getObjectId())).findFirst().orElseThrow();
		var waiting = rows.stream().filter(r -> "2".equals(r.getObjectId())).findFirst().orElseThrow();
		var paused = rows.stream().filter(r -> "3".equals(r.getObjectId())).findFirst().orElseThrow();

		assertEquals("INCOMPLETE", missingNotice.getQueueResidence().status());
		assertEquals("7200", missingNotice.getSinceFirstStart().seconds());
		assertNull(waiting.getSinceFirstStart());
		assertEquals("INCOMPLETE", paused.getSinceFirstStart().status());
		assertTrue(paused.getIssues().contains("MISSING_START_TIME"));
		assertNull(rows.stream().filter(r -> "4".equals(r.getObjectId())).findFirst().orElseThrow().getSinceFirstStart());
		assertNull(rows.stream().filter(r -> "5".equals(r.getObjectId())).findFirst().orElseThrow().getSinceFirstStart());
		assertEquals("7200", rows.stream().filter(r -> "6".equals(r.getObjectId())).findFirst().orElseThrow().getSinceFirstStart().seconds());
	}

	@Test
	void futureInvertedAndContradictoryTimesAreUnknown() {
		process(1, "PRODUCT", observed.minusHours(2), observed.plusNanos(1000));
		process(2, "PRODUCT", observed.plusNanos(1000), null);
		process(3, "PRODUCT", observed.minusHours(1), observed.minusHours(2));
		process(4, "PRODUCT", observed.minusHours(2), observed.minusHours(1));
		db.update("UPDATE mom_mes.mes_produce_task SET finish_time=? WHERE id=4", observed.minusMinutes(1));
		var rows = rows(new Query());

		assertEquals("4", summary(new Query()).getUnknownQueueAgeCount());
		assertEquals(List.of("FUTURE_START_TIME", "FUTURE_NOTICE_TIME", "INVERTED_NOTICE_START", "CONTRADICTORY_FINISH_TIME"),
				rows.stream().sorted(java.util.Comparator.comparing(Row::getObjectId)).map(r -> r.getQueueResidence().reason()).toList());
		assertNull(rows.stream().filter(r -> "3".equals(r.getObjectId())).findFirst().orElseThrow().getSinceFirstStart().seconds());
	}

	@Test
	void duplicateAndOrphanSourcesNeverMultiplyOrDisappear() {
		process(1, "WAIT", observed.minusDays(2), null);
		process(2, "WAIT", observed.minusDays(2), null);
		process(3, "WAIT", observed.minusDays(2), null);
		process(4, "WAIT", observed.minusDays(2), null);
		db.update("UPDATE mom_mes.mes_produce_task SET code=' DUP ' WHERE id=1");
		db.update("UPDATE mom_mes.mes_produce_task SET code='DUP' WHERE id=2");
		db.update("UPDATE mom_mes.mes_produce_task SET task_code='MISSING' WHERE id=3");
		db.update("UPDATE mom_mes.mes_produce_task SET route_code=NULL WHERE id=4");
		db.update("INSERT INTO mom_mes.mes_task VALUES(101,'MISSING',2,'F1','0')");
		db.update("INSERT INTO mom_mes.mes_task VALUES(102,'MISSING',1,'F2','0')");
		db.update("INSERT INTO mom_mes.mes_task VALUES(103,'MISSING',1,'F1','1')");

		assertEquals("4", summary(new Query()).getPopulationCount());
		assertEquals("4", summary(new Query()).getSourceIssueCount());
		assertEquals("4", summary(new Query()).getUnknownQueueAgeCount());
		assertEquals(4, rows(new Query()).size());
		assertEquals("4", summary(new Query()).getWaitingDispatchCount());
		assertEquals("0", mapper.detailTotal(1L, "F1", new Query().setStatus("UNKNOWN"), observed, cutoff()));
	}

	@Test
	void parentDuplicatesUseNormalizedSameOrganizationIdentityOnly() {
		process(1, "WAIT", observed.minusDays(2), null);
		db.update("INSERT INTO mom_mes.mes_task VALUES(101,' PARENT ',1,'F1','0')");

		assertEquals("1", summary(new Query()).getSourceIssueCount());
		assertEquals(1, rows(new Query()).size());
	}

	@Test
	void displayedNormalizedCodesFilterTheSameRowsAndGroups() {
		process(1, "WAIT", observed.minusDays(2), null);
		db.update("UPDATE mom_mes.mes_task SET code=' PARENT '");
		db.update("UPDATE mom_mes.mes_produce_task SET code=' P1 ',task_code=' PARENT ',route_code=' R1 ',"
				+ "workshop_code=' W1 ',source_route_node_code=' N1 ',mat_code=' M1 '");
		var q = new Query().setTaskCode("PARENT").setRouteCode("R1").setWorkshopCode("W1").setStatus("WAIT");
		var row = rows(q).get(0);

		assertEquals("1", summary(q).getKnownQueueAgeCount());
		assertEquals("P1", row.getObjectCode());
		assertEquals("PARENT", row.getTaskCode());
		assertEquals("R1", row.getRouteCode());
		assertEquals("W1", row.getWorkshopCode());
		assertEquals("N1", row.getRouteNodeCode());
		assertEquals("M1", row.getMatCode());
		assertEquals("WAIT", row.getStatus());
		assertEquals("R1", mapper.groups(1L, "F1", q.setGroupBy("ROUTE"), observed, cutoff()).get(0).getGroupCode());
	}

	@Test
	void foreignDeletedAndTerminalRowsDoNotContaminateCurrentPopulation() {
		for (int i = 1; i <= 6; i++) {
			process(i, "WAIT", observed.minusDays(2), null);
		}
		db.update("UPDATE mom_mes.mes_produce_task SET tenant_id=2 WHERE id=2");
		db.update("UPDATE mom_mes.mes_produce_task SET factory_code='F2' WHERE id=3");
		db.update("UPDATE mom_mes.mes_produce_task SET del_flag='1' WHERE id=4");
		db.update("UPDATE mom_mes.mes_produce_task SET status='FINISH' WHERE id=5");
		db.update("UPDATE mom_mes.mes_produce_task SET status='CLOSE' WHERE id=6");

		assertEquals("1", summary(new Query()).getPopulationCount());
	}

	@Test
	void aggregationCoversAllPagesWithFixedThirtyDetails() {
		for (int i = 1; i <= 35; i++) {
			process(i, "WAIT", observed.minusDays(2), null);
		}
		var q = new Query();

		assertEquals("35", summary(q).getOverThresholdCount());
		assertEquals("35", mapper.detailTotal(1L, "F1", q, observed, cutoff()));
		assertEquals(30, mapper.details(1L, "F1", q, observed, cutoff(), 0L).size());
		assertEquals(5, mapper.details(1L, "F1", q, observed, cutoff(), 30L).size());
		assertEquals("35", mapper.groups(1L, "F1", q, observed, cutoff()).get(0).getPopulationCount());
	}

	@Test
	void trueUnknownAndNullGroupsDrillIndependentlyAndCoverageIgnoresWorkshop() {
		process(1, "WAIT", observed.minusDays(2), null);
		process(2, "WAIT", observed.minusDays(2), null);
		process(3, "WAIT", observed.minusDays(2), null);
		db.update("UPDATE mom_mes.mes_produce_task SET workshop_code='UNKNOWN' WHERE id=1");
		db.update("UPDATE mom_mes.mes_produce_task SET workshop_code='  ' WHERE id=2");
		var q = new Query();

		assertEquals(3, mapper.groups(1L, "F1", q, observed, cutoff()).size());
		assertEquals("1", mapper.detailTotal(1L, "F1", q.setSelectedGroupCode("UNKNOWN"), observed, cutoff()));
		assertEquals("1", mapper.detailTotal(1L, "F1", q.setSelectedGroupCode(null).setSelectedGroupUnattributed(true), observed, cutoff()));
		var filtered = new Query().setWorkshopCode("W1");
		var coverage = mapper.coverage(1L, "F1", filtered, observed, cutoff());
		assertEquals("1", coverage.getUnattributedCount());
		assertEquals("INCOMPLETE", calculator.summary(mapper.summary(1L, "F1", filtered, observed, cutoff()),
				observed, calculator.coverageIncomplete(filtered, coverage)).getOverThresholdRate().status());
	}

	@Test
	void oneThousandOneGroupsSignalOverflowWithoutTruncatingSummary() {
		for (int i = 1; i <= 1001; i++) {
			process(i, "WAIT", observed.minusDays(2), null);
			db.update("UPDATE mom_mes.mes_produce_task SET workshop_code=? WHERE id=?", "W" + i, i);
		}

		assertEquals(1001, mapper.groups(1L, "F1", new Query(), observed, cutoff()).size());
		assertEquals("1001", summary(new Query()).getPopulationCount());
	}

	@Test
	void oldestNoticeAndMaximumAgeUseOnlyTheCalculableSubset() {
		process(1, "WAIT", observed.minusDays(10), null);
		process(2, "WAIT", observed.minusHours(2), null);
		db.update("UPDATE mom_mes.mes_produce_task SET task_code='ORPHAN' WHERE id=1");
		var s = summary(new Query());

		assertEquals("2", s.getPopulationCount());
		assertEquals("1", s.getKnownQueueAgeCount());
		assertEquals("2026-10-23T10:00:00.123456", s.getOldestNoticeTime());
		assertEquals("7200", s.getMaxQueueResidenceSeconds());
	}

	@Test
	void coverageAppliesBaseCodesWhileIgnoringWorkshopSelection() {
		process(1, "WAIT", observed.minusDays(2), null);
		process(2, "WAIT", observed.minusDays(2), null);
		db.update("UPDATE mom_mes.mes_produce_task SET workshop_code=NULL");
		db.update("UPDATE mom_mes.mes_produce_task SET task_code='OTHER',route_code='OTHER' WHERE id=2");

		assertEquals("1", mapper.coverage(1L, "F1", new Query().setTaskCode("PARENT").setRouteCode("R1")
				.setWorkshopCode("W1"), observed, cutoff()).getUnattributedCount());
	}

	@Test
	void noPopulationHasNoInventedAgeOrPercentage() {
		var s = summary(new Query());

		assertEquals("0", s.getPopulationCount());
		assertNull(s.getOldestNoticeTime());
		assertNull(s.getMaxQueueResidenceSeconds());
		assertEquals("NOT_APPLICABLE", s.getOverThresholdRate().status());
	}

	private Summary summary(Query q) {
		return calculator.summary(mapper.summary(1L, "F1", q, observed, cutoff()), observed, false);
	}

	private List<Row> rows(Query q) {
		return mapper.details(1L, "F1", q, observed, cutoff(), 0L).stream().map(f -> calculator.row(f, observed)).toList();
	}

	private LocalDateTime cutoff() {
		return observed.minusHours(24);
	}

	private void process(long id, String status, LocalDateTime notice, LocalDateTime start) {
		db.update("INSERT INTO mom_mes.mes_produce_task VALUES(?,?,'PARENT',1,'F1','0','W1','R1','N1','M1',?,?,?,NULL)",
				id, "P" + id, status, notice, start);
	}
}
