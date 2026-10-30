package com.kitex.mom.mes.service.impl;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Actual scoped SQL, including historical terminal rows; PostgreSQL deployment is verified separately. */
class MesDispatchHistoryMapperTest {
	private JdbcTemplate db;
	private SqlSession session;
	private static final String NS = "com.kitex.mom.mes.mapper.MesDispatchHistoryMapper.";

	@BeforeEach
	void setup() throws Exception {
		var source = new DriverManagerDataSource("jdbc:h2:mem:dispatch-history-" + UUID.randomUUID()
				+ ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
		source.setDriverClassName("org.h2.Driver");
		db = new JdbcTemplate(source);
		db.execute("CREATE SCHEMA mom_mes");
		db.execute("CREATE TABLE mom_mes.mes_task_arrange(id bigint PRIMARY KEY,tenant_id bigint,factory_code varchar,"
				+ "code varchar,task_code varchar,produce_task_code varchar,workshop_code varchar,shift_code varchar,"
				+ "worker_code varchar,worksite_codes varchar,actual_worksite_code varchar,status varchar,"
				+ "execution_template_code varchar,execution_template_version integer,dispatcher_time timestamp,"
				+ "start_time timestamp,finish_time timestamp,del_flag varchar)");
		row(1, 1, "F1", "A1", "T1", "FINISH", "2026-10-22 08:00:00");
		row(2, 1, "F1", "A10", "T10", "CLOSE", "2026-10-22 09:00:00");
		row(3, 2, "F1", "A1", "T1", "FINISH", "2026-10-22 08:00:00");
		row(4, 1, "F2", "A1", "T1", "FINISH", "2026-10-22 08:00:00");
		Configuration config = new Configuration();
		config.setMapUnderscoreToCamelCase(true);
		config.setEnvironment(new Environment("history", new JdbcTransactionFactory(), source));
		try (var xml = getClass().getResourceAsStream("/mapper/MesDispatchHistoryMapper.xml")) {
			assertNotNull(xml, "Dedicated exact history SQL has not been implemented");
			new XMLMapperBuilder(xml, config, "MesDispatchHistoryMapper.xml", config.getSqlFragments()).parse();
		}
		session = new SqlSessionFactoryBuilder().build(config).openSession(true);
	}

	@AfterEach
	void close() {
		if (session != null) {
			session.close();
		}
	}

	@Test
	void similarCodesAndOtherOrganizationsCannotMatchExactTask() {
		var q = new HashMap<String, Object>();
		q.put("taskCode", "T1");

		assertEquals(List.of(1L), ids(q));
		assertEquals(1L, count(q));
	}

	@Test
	void dateWindowIsHalfOpenAndAllTerminalStatusesRemainVisible() {
		var q = new HashMap<String, Object>();
		q.put("startTime", LocalDateTime.parse("2026-10-22T08:00:00"));
		q.put("endTime", LocalDateTime.parse("2026-10-22T09:00:00"));

		assertEquals(List.of(1L), ids(q));
		q.put("endTime", LocalDateTime.parse("2026-10-22T10:00:00"));
		assertEquals(List.of(2L, 1L), ids(q));
	}

	@Test
	void workerAndWorksiteFiltersUseWholeCsvMembers() {
		db.update("UPDATE mom_mes.mes_task_arrange SET worker_code='U10,U2',worksite_codes='S10,S2' WHERE id=2");
		assertEquals(List.of(1L), ids(new HashMap<>(Map.of("workerCode", "U1"))));
		assertEquals(List.of(1L), ids(new HashMap<>(Map.of("worksiteCode", "S1"))));
		assertEquals(List.of(2L), ids(new HashMap<>(Map.of("worksiteCode", "S2"))));
	}

	@Test
	void paginationHasStableIdTieBreakAndSameTotal() {
		row(5, 1, "F1", "A5", "T1", "FINISH", "2026-10-22 08:00:00");
		var p = params(new HashMap<>());
		p.put("limit", 1);
		p.put("offset", 1L);
		List<com.kitex.mom.mes.entity.MesTaskArrangeEntity> rows = session.selectList(NS + "page", p);

		assertEquals(5L, rows.get(0).getId());
		assertEquals(3L, count(new HashMap<>()));
	}

	@Test
	void dateBasisUsesChosenRealTimestampAndDoesNotFallbackMissingDates() {
		db.update("UPDATE mom_mes.mes_task_arrange SET finish_time='2026-10-22 09:30:00' WHERE id=1");
		var q = new HashMap<String, Object>();
		q.put("dateBasis", "FINISH");
		q.put("startTime", LocalDateTime.parse("2026-10-22T09:00:00"));
		q.put("endTime", LocalDateTime.parse("2026-10-22T10:00:00"));

		assertEquals(List.of(1L), ids(q));
	}

	private List<Long> ids(HashMap<String, Object> q) {
		List<com.kitex.mom.mes.entity.MesTaskArrangeEntity> rows = session.selectList(NS + "page", params(q));
		return rows.stream().map(com.kitex.mom.mes.entity.MesTaskArrangeEntity::getId).toList();
	}

	private long count(HashMap<String, Object> q) {
		return session.selectOne(NS + "total", params(q));
	}

	private Map<String, Object> params(HashMap<String, Object> q) {
		q.putIfAbsent("dateBasis", "DISPATCH");
		return new HashMap<>(Map.of("tenant", 1L, "factory", "F1", "q", q, "limit", 100, "offset", 0L));
	}

	private void row(long id, long tenant, String factory, String code, String task, String status, String time) {
		db.update("INSERT INTO mom_mes.mes_task_arrange VALUES(?,?,?, ?,?,'P1','W1','SHIFT','U1','S1',NULL,?,"
				+ "'EXEC',2,?,NULL,NULL,'0')", id, tenant, factory, code, task, status, LocalDateTime.parse(time.replace(' ', 'T')));
	}
}
