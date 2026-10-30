package com.kitex.mom.mes.service.impl;

import com.kitex.mom.mes.dto.MesPlanAttainmentDTO.*;
import com.kitex.mom.mes.mapper.MesPlanAttainmentMapper;
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

/** 实际XML与H2验证，不代替原生PG、组织拦截器或HTTP认证链路联验。 */
class MesPlanAttainmentMapperTest {

	private JdbcTemplate db;
	private SqlSession session;
	private MesPlanAttainmentMapper mapper;
	private final LocalDateTime observed = LocalDateTime.parse("2026-10-23T12:00:00");

	@BeforeEach
	void setup() throws Exception {
		var source = new DriverManagerDataSource("jdbc:h2:mem:plan-attainment-" + UUID.randomUUID()
				+ ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
		source.setDriverClassName("org.h2.Driver");
		db = new JdbcTemplate(source);
		db.execute("CREATE SCHEMA mom_mes");
		db.execute("CREATE TABLE mom_mes.mes_task(id bigint primary key,code varchar,tenant_id bigint,"
				+ "factory_code varchar,del_flag varchar,workshop_code varchar,status varchar,"
				+ "plan_end_date timestamp,start_time timestamp,finish_time timestamp)");
		db.execute("CREATE TABLE mom_mes.mes_produce_task(id bigint primary key,code varchar,task_code varchar,"
				+ "tenant_id bigint,factory_code varchar,del_flag varchar,workshop_code varchar,route_code varchar,"
				+ "source_route_node_code varchar,status varchar,planned_end_time timestamp,start_time timestamp,finish_time timestamp)");
		Configuration configuration = new Configuration();
		configuration.setMapUnderscoreToCamelCase(true);
		configuration.setEnvironment(new Environment("plan-attainment", new JdbcTransactionFactory(), source));
		try (var xml = getClass().getResourceAsStream("/mapper/MesPlanAttainmentMapper.xml")) {
			assertNotNull(xml);
			new XMLMapperBuilder(xml, configuration, "MesPlanAttainmentMapper.xml", configuration.getSqlFragments()).parse();
		}
		session = new SqlSessionFactoryBuilder().build(configuration).openSession(true);
		mapper = session.getMapper(MesPlanAttainmentMapper.class);
	}

	@AfterEach
	void close() {
		if (session != null) {
			session.close();
		}
	}

	@Test
	void dueWindowIsHalfOpenAndOnTimeIncludesExactDeadline() {
		task(9007199254740993L, "T1", "FINISH", "2026-10-22T00:00:00", "2026-10-22T00:00:00", "W1");
		task(2, "T2", "FINISH", "2026-10-23T00:00:00", "2026-10-22T01:00:00", "W1");
		task(3, "T3", "PRODUCT", "2026-10-21T23:59:59", null, "W1");
		var totals = summary(query());
		var rows = mapper.details(1L, "F1", query(), observed, 0L);

		assertEquals("1", totals.getDueCount());
		assertEquals("1", totals.getOnTimeCount());
		assertEquals("100.0000", totals.getCompletionRate().percentage());
		assertEquals("9007199254740993", rows.get(0).getObjectId());
	}

	@Test
	void disjointCategoriesAndExplicitClosedPolicyKeepBothIdentities() {
		task(1, "T1", "FINISH", "2026-10-22T08:00:00", "2026-10-22T08:00:00", "W1");
		task(2, "T2", "FINISH", "2026-10-22T08:00:00", "2026-10-22T09:00:00", "W1");
		task(3, "T3", "ISSUE", "2026-10-22T08:00:00", null, "W1");
		task(4, "T4", "CLOSE", "2026-10-22T08:00:00", null, "W1");
		task(5, "T5", "CREATE", "2026-10-22T08:00:00", null, "W1");
		task(6, "T6", "ALIEN", "2026-10-22T08:00:00", null, "W1");
		var included = summary(query());
		var excluded = summary(query().setClosedPolicy("EXCLUDE_CLOSED"));

		assertEquals("6", included.getDueCount());
		assertEquals("5", included.getApplicableCount());
		assertEquals("2", included.getCompletedCount());
		assertEquals("1", included.getClosedCount());
		assertEquals("1", included.getDraftCount());
		assertEquals("1", included.getUnknownCount());
		assertEquals("INCOMPLETE", included.getCompletionRate().status());
		assertEquals("4", excluded.getApplicableCount());
		assertEquals("1", excluded.getClosedCount());
		assertEquals("1", mapper.detailTotal(1L, "F1", query().setCategory("CLOSED"), observed));
	}

	@Test
	void contradictoryOrMissingCompletionEvidenceRemainsUnknown() {
		task(1, "T1", "FINISH", "2026-10-22T08:00:00", null, "W1");
		task(2, "T2", "FINISH", "2026-10-22T08:00:00", "2026-10-24T00:00:00", "W1");
		task(3, "T3", "PRODUCT", "2026-10-22T08:00:00", "2026-10-22T09:00:00", "W1");
		task(4, "T4", "FINISH", "2026-10-22T08:00:00", "2026-10-22T09:00:00", "W1");
		db.update("UPDATE mom_mes.mes_task SET start_time='2026-10-22 10:00:00' WHERE id=4");
		var totals = summary(query());

		assertEquals("4", totals.getUnknownCount());
		assertEquals("0", totals.getCompletedCount());
		assertNull(totals.getOnTimeRate().percentage());
		assertEquals(4, mapper.details(1L, "F1", query().setCategory("UNKNOWN"), observed, 0L).size());
	}

	@Test
	void missingDueCoverageCannotBeCalledWindowZeroAndWorkshopFilterCannotHideUnattributed() {
		task(1, "T1", "FINISH", "2026-10-22T08:00:00", "2026-10-22T08:00:00", "W1");
		task(2, "T2", "PRODUCT", "2026-10-22T08:00:00", null, null);
		task(3, "T3", "PRODUCT", null, null, "W1");
		task(4, "T4", "CREATE", null, null, "W1");
		var q = query().setWorkshopCode("W1");
		var coverage = mapper.coverage(1L, "F1", q, observed);
		var total = new MesPlanAttainmentCalculator().summary(mapper.summary(1L, "F1", q, observed), q,
				new MesPlanAttainmentCalculator().coverageIncomplete(q, coverage));

		assertEquals("1", coverage.getMissingDueCount());
		assertEquals("1", coverage.getUnattributedDueCount());
		assertEquals("1", total.getCompletedCount());
		assertEquals("1", total.getApplicableCount());
		assertEquals("INCOMPLETE", total.getCompletionRate().status());
	}

	@Test
	void processParentDuplicatesAreCountedOnceWithoutCrossOrganizationMatches() {
		task(1, "PARENT", "PRODUCT", null, null, "W1");
		task(2, "PARENT", "PRODUCT", null, null, "W1");
		process(1, "P1", "PARENT", "R1", "W1");
		process(2, "P2", "MISSING", "R1", "W1");
		db.update("INSERT INTO mom_mes.mes_task VALUES(3,'MISSING',2,'F1','0','W1','PRODUCT',NULL,NULL,NULL)");
		db.update("INSERT INTO mom_mes.mes_task VALUES(4,'MISSING',1,'F2','0','W1','PRODUCT',NULL,NULL,NULL)");
		var q = query().setObjectLevel("PROCESS").setGroupBy("ROUTE");
		var total = summary(q);

		assertEquals("2", total.getDueCount());
		assertEquals("2", total.getUnknownCount());
		assertEquals(2, mapper.details(1L, "F1", q, observed, 0L).size());
		assertEquals("2", mapper.groups(1L, "F1", q, observed).get(0).getDueCount());
	}

	@Test
	void duplicateCodesAndForeignRowsNeverBecomeHealthyCompletion() {
		task(1, "DUP", "FINISH", "2026-10-22T08:00:00", "2026-10-22T08:00:00", "W1");
		task(2, "DUP", "FINISH", "2026-10-22T08:00:00", "2026-10-22T08:00:00", "W1");
		db.update("INSERT INTO mom_mes.mes_task VALUES(3,'DUP',2,'F1','0','W1','FINISH','2026-10-22 08:00:00',NULL,'2026-10-22 08:00:00')");
		db.update("INSERT INTO mom_mes.mes_task VALUES(4,'DUP',1,'F2','0','W1','FINISH','2026-10-22 08:00:00',NULL,'2026-10-22 08:00:00')");
		db.update("INSERT INTO mom_mes.mes_task VALUES(5,'DUP',1,'F1','1','W1','FINISH','2026-10-22 08:00:00',NULL,'2026-10-22 08:00:00')");

		assertEquals("2", summary(query()).getUnknownCount());
		assertEquals("2", summary(query()).getDueCount());
	}

	@Test
	void actualAggregationCoversAllPagesAndRepeatedRouteNodesCountAsInstances() {
		task(100, "PARENT", "PRODUCT", null, null, "W1");
		for (int i = 1; i <= 35; i++) {
			process(i, "P" + i, "PARENT", "R1", "W1");
		}
		var q = query().setObjectLevel("PROCESS").setGroupBy("ROUTE");

		assertEquals("35", summary(q).getDueCount());
		assertEquals("35", mapper.detailTotal(1L, "F1", q, observed));
		assertEquals(30, mapper.details(1L, "F1", q, observed, 0L).size());
		assertEquals(5, mapper.details(1L, "F1", q, observed, 30L).size());
		assertEquals("35", mapper.groups(1L, "F1", q, observed).get(0).getDueCount());
	}

	@Test
	void literalUnknownAndNullGroupsHaveIndependentDrilldown() {
		task(1, "T1", "PRODUCT", "2026-10-22T08:00:00", null, "UNKNOWN");
		task(2, "T2", "PRODUCT", "2026-10-22T08:00:00", null, null);
		var groups = mapper.groups(1L, "F1", query(), observed);

		assertEquals(2, groups.size());
		assertEquals("1", mapper.detailTotal(1L, "F1", query().setSelectedGroupCode("UNKNOWN"), observed));
		assertEquals("1", mapper.detailTotal(1L, "F1", query().setSelectedGroupUnattributed(true), observed));
	}

	@Test
	void groupQuerySignalsAboveOneThousandWithoutTruncatingSummary() {
		for (int i = 1; i <= 1001; i++) {
			task(i, "T" + i, "PRODUCT", "2026-10-22T08:00:00", null, "W" + i);
		}

		assertEquals(1001, mapper.groups(1L, "F1", query(), observed).size());
		assertEquals("1001", summary(query()).getDueCount());
	}

	@Test
	void closeWithCompletionEvidenceIsContradictoryAndCannotBeSilentlyExcluded() {
		task(1, "T1", "CLOSE", "2026-10-22T08:00:00", "2026-10-22T08:00:00", "W1");
		var included = summary(query());
		var excluded = summary(query().setClosedPolicy("EXCLUDE_CLOSED"));

		assertEquals("1", included.getUnknownCount());
		assertEquals("0", included.getCompletedCount());
		assertEquals("INCOMPLETE", excluded.getCompletionRate().status());
		assertEquals("1", excluded.getApplicableCount());
		assertEquals("CONTRADICTORY_FINISH_TIME", mapper.details(1L, "F1", query(), observed, 0L).get(0).getIssue());
	}

	@Test
	void whitespaceCodeNormalizationCannotHideDuplicateIdentity() {
		task(1, "T1", "FINISH", "2026-10-22T08:00:00", "2026-10-22T08:00:00", "W1");
		task(2, " T1 ", "FINISH", "2026-10-22T08:00:00", "2026-10-22T08:00:00", "W1");

		assertEquals("2", summary(query()).getUnknownCount());
		assertEquals("0", summary(query()).getCompletedCount());
	}

	@Test
	void allKnownUnfinishedProcessStatesStayOpenAndMissingRouteStaysUnknown() {
		task(100, "PARENT", "PRODUCT", null, null, "W1");
		String[] states = {"WAIT", "UN_RECEIVE", "RECEIVE", "PRODUCT", "PAUSE"};
		for (int i = 0; i < states.length; i++) {
			process(i + 1, "P" + i, "PARENT", "R1", "W1");
			db.update("UPDATE mom_mes.mes_produce_task SET status=?,finish_time=NULL WHERE id=?", states[i], i + 1);
		}
		process(10, "NO_ROUTE", "PARENT", null, "W1");
		var q = query().setObjectLevel("PROCESS").setGroupBy("ROUTE");

		assertEquals("5", summary(q).getOpenCount());
		assertEquals("1", summary(q).getUnknownCount());
		assertEquals("0", summary(q).getCompletedCount());
		assertEquals(2, mapper.groups(1L, "F1", q, observed).size());
		assertEquals("1", mapper.detailTotal(1L, "F1", q.setSelectedGroupUnattributed(true), observed));
	}

	@Test
	void deletedParentAndDuplicateProcessCodesRemainDistinctUnknownObjects() {
		task(100, "PARENT", "PRODUCT", null, null, "W1");
		process(1, "DUP", "PARENT", "R1", "W1");
		process(2, "DUP", "PARENT", "R1", "W1");
		process(3, "NO_PARENT", "DELETED", "R1", "W1");
		task(101, "DELETED", "PRODUCT", null, null, "W1");
		db.update("UPDATE mom_mes.mes_task SET del_flag='1' WHERE id=101");
		var q = query().setObjectLevel("PROCESS");

		assertEquals("3", summary(q).getDueCount());
		assertEquals("3", summary(q).getUnknownCount());
		assertEquals(3, mapper.details(1L, "F1", q, observed, 0L).size());
	}

	@Test
	void missingDueCoverageRespectsExplicitClosedPolicyAndExcludesDrafts() {
		task(1, "T1", "CLOSE", null, null, "W1");
		task(2, "T2", "CREATE", null, null, "W1");
		task(3, "T3", "ALIEN", null, null, "W1");

		assertEquals("2", mapper.coverage(1L, "F1", query(), observed).getMissingDueCount());
		assertEquals("1", mapper.coverage(1L, "F1", query().setClosedPolicy("EXCLUDE_CLOSED"), observed).getMissingDueCount());
		assertEquals("0", summary(query()).getDueCount());
		assertEquals("NOT_APPLICABLE", summary(query()).getCompletionRate().status());
	}

	@Test
	void detailRowsExposeIsoDatesCategoryAndEvidenceIssue() {
		task(1, "T1", "FINISH", "2026-10-22T08:00:00", null, null);
		var fact = mapper.details(1L, "F1", query(), observed, 0L).get(0);
		var row = new MesPlanAttainmentCalculator().row(fact);

		assertEquals("2026-10-22T08:00:00", row.getPlannedEndTime());
		assertNull(row.getFinishTime());
		assertEquals("UNKNOWN", row.getCategory());
		assertEquals(java.util.List.of("MISSING_FINISH_TIME", "MISSING_FROZEN_WORKSHOP"), row.getIssues());
	}

	@Test
	void displayedTaskCodeCanFilterTheSamePaddedSourceObject() {
		task(1, " T1 ", "PRODUCT", "2026-10-22T08:00:00", null, "W1");
		var q = query().setTaskCode("T1");
		var allRows = mapper.details(1L, "F1", query(), observed, 0L);

		assertEquals("T1", allRows.get(0).getObjectCode());
		assertEquals("1", summary(q).getDueCount());
		assertEquals("1", mapper.detailTotal(1L, "F1", q, observed));
		assertEquals("T1", mapper.details(1L, "F1", q, observed, 0L).get(0).getObjectCode());
	}

	@Test
	void displayedProcessParentAndRouteCodesCanFilterTheSamePaddedSourceObject() {
		task(1, " PARENT ", "PRODUCT", null, null, "W1");
		process(1, " P1 ", " PARENT ", " R1 ", "W1");
		var q = query().setObjectLevel("PROCESS").setGroupBy("ROUTE").setTaskCode("PARENT").setRouteCode("R1");

		assertEquals("1", summary(q).getDueCount());
		assertEquals("0", summary(q).getUnknownCount());
		assertEquals("R1", mapper.groups(1L, "F1", q, observed).get(0).getGroupCode());
		assertEquals("P1", mapper.details(1L, "F1", q, observed, 0L).get(0).getObjectCode());
	}

	@Test
	void missingDeadlineCoverageRetainsAnomalousDraftsAndClosedObjects() {
		task(1, "CLOSE_ANOMALY", "CLOSE", null, "2026-10-22T08:00:00", "W1");
		task(2, "DRAFT_ANOMALY", "CREATE", null, "2026-10-22T08:00:00", "W1");
		task(3, "DUP", "CREATE", null, null, "W1");
		task(4, " DUP ", "CREATE", null, null, "W1");
		task(5, "DRAFT_VALID", "CREATE", null, null, "W1");
		task(6, "CLOSE_VALID", "CLOSE", null, null, "W1");
		task(7, "OPEN", "ISSUE", null, null, "W1");

		assertEquals("5", mapper.coverage(1L, "F1", query().setClosedPolicy("EXCLUDE_CLOSED"), observed).getMissingDueCount());
		assertEquals("6", mapper.coverage(1L, "F1", query(), observed).getMissingDueCount());
	}

	@Test
	void processCreateIsUnknownAndMissingParentsCannotDisappearFromDeadlineCoverage() {
		task(1, "PARENT", "PRODUCT", null, null, "W1");
		process(1, "P1", "PARENT", "R1", "W1");
		process(2, "P2", "MISSING", "R1", "W1");
		process(3, "P3", "PARENT", "R1", "W1");
		process(4, "P4", "PARENT", "R1", "W1");
		process(5, "P5", "PARENT", "R1", "W1");
		db.update("UPDATE mom_mes.mes_produce_task SET planned_end_time=NULL");
		db.update("UPDATE mom_mes.mes_produce_task SET status='CREATE',finish_time=NULL WHERE id=1");
		db.update("UPDATE mom_mes.mes_produce_task SET status='CLOSE' WHERE id=2 OR id=3");
		db.update("UPDATE mom_mes.mes_produce_task SET status='CLOSE',finish_time=NULL WHERE id=4");
		db.update("UPDATE mom_mes.mes_produce_task SET status='WAIT',finish_time=NULL WHERE id=5");
		var q = query().setObjectLevel("PROCESS").setClosedPolicy("EXCLUDE_CLOSED");

		assertEquals("4", mapper.coverage(1L, "F1", q, observed).getMissingDueCount());
		assertEquals("5", mapper.coverage(1L, "F1", q.setClosedPolicy("INCLUDE_UNFINISHED"), observed).getMissingDueCount());
	}

	private Summary summary(Query q) {
		return new MesPlanAttainmentCalculator().summary(mapper.summary(1L, "F1", q, observed), q, false);
	}

	private void task(long id, String code, String status, String due, String finish, String workshop) {
		db.update("INSERT INTO mom_mes.mes_task VALUES(?,?,1,'F1','0',?,?,?,NULL,?)",
				id, code, workshop, status, date(due), date(finish));
	}

	private void process(long id, String code, String parent, String route, String workshop) {
		db.update("INSERT INTO mom_mes.mes_produce_task VALUES(?,?,?,1,'F1','0',?,?,'NODE'||?,'FINISH',"
				+ "'2026-10-22 08:00:00',NULL,'2026-10-22 08:00:00')", id, code, parent, workshop, route, id);
	}

	private LocalDateTime date(String value) {
		return value == null ? null : LocalDateTime.parse(value);
	}

	private Query query() {
		return new Query().setObjectLevel("TASK").setGroupBy("WORKSHOP")
				.setStartTime(LocalDateTime.parse("2026-10-22T00:00:00"))
				.setEndTime(LocalDateTime.parse("2026-10-23T00:00:00"));
	}
}
