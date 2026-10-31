package com.kitex.mom.qms.service.impl;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.*;
import java.util.*;

/** 执行366实际完整旧/新ready核验SELECT；H2目录替身不认证目标PG触发器。 */
class QmsProcedureDispositionReadySchemaTest {
	static final String MES = "mom_mes.mom_inspection_event_inbox_ready_v1()",
			QMS = "mom_qms.mom_inspection_event_ready_v1()";

	record Fixture(JdbcTemplate db, String query, String knownOld, String knownNew, List<Object> parameters, List<String> knownSources) {
		boolean allowed() {
			return Boolean.TRUE.equals(db.queryForObject(query, Boolean.class, parameters.toArray()));
		}
	}

	Fixture fixture(String proc) throws Exception {
		String script =
				Files.readString(
						Files.exists(
										Path.of(
												"../../../docs/database/mom-cumulative-upgrade-pg-v4.sql"))
								? Path.of("../../../docs/database/mom-cumulative-upgrade-pg-v4.sql")
								: Path.of("Code/docs/database/mom-cumulative-upgrade-pg-v4.sql"));
		int section = script.indexOf("-- 366. 普通工序");
		int at = script.indexOf("366未知完整基础ready" + proc, section);
		assertTrue(section > 0 && at > section, "升级必须核完整旧ready目录定义");
		int begin = script.lastIndexOf("SELECT EXISTS(", at),
				end = script.indexOf(" INTO known_ready;", begin);
		assertTrue(begin > section && end > begin && end < at);
		String query =
				script.substring(begin, end)
						.replace("to_regprocedure('" + proc + "')", "'" + proc + "'")
						.replace("::regtype", "");
		JdbcTemplate db =
				new JdbcTemplate(
						new DriverManagerDataSource(
								"jdbc:h2:mem:ready365_"
										+ UUID.randomUUID()
										+ ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
								"sa",
								""));
		db.execute("CREATE TABLE pg_language(oid bigint PRIMARY KEY,lanname varchar)");
		db.execute("INSERT INTO pg_language VALUES(1,'plpgsql')");
		db.execute(
				"CREATE TABLE pg_proc(oid varchar PRIMARY KEY,prolang bigint,prosrc"
					+ " varchar,prorettype varchar,prokind varchar,prosecdef boolean,provolatile"
					+ " varchar,proisstrict boolean,proconfig varchar ARRAY,proparallel"
					+ " varchar,proleakproof boolean,proretset boolean,pronargs"
					+ " integer,pronargdefaults integer,proallargtypes varchar,proargmodes"
					+ " varchar)");
		String
				old =
						"BEGIN -- 2ac8edd470a8bed0c752449f6bdd36a7"
								+ " f33e23323764cf1e1836030ddb8965f2\n"
								+ " -- table signature AND c.contype<>''t''\n IF trusted_shape()<>1 THEN RETURN 0;END IF;RETURN 1;END;",
				next = old.replace("trusted_shape", "new_trusted_shape");
		db.update(
				"INSERT INTO pg_proc VALUES(?,1,?,'integer','f',false,'v',false,ARRAY[CAST(? AS"
						+ " VARCHAR)],'u',false,false,0,0,null,null)",
				proc,
				old,
				"search_path=pg_catalog, " + proc.split("\\.")[0]);
		// The accepted candidates are fixed independently of the query being tested.
		Map<String, String> sources = new LinkedHashMap<>();
		sources.put("old_ready", old);
		sources.put("new_ready", next);
		if (proc.equals(MES)) {
			sources.put("scope_old_ready", old.replace("trusted_shape", "legacy_trusted_shape"));
			sources.put("scope_new_ready", next.replace("new_trusted_shape", "legacy_new_trusted_shape"));
		}
		List<String> knownSources = new ArrayList<>();
		for (String source : sources.values()) {
			knownSources.add(source);
			knownSources.add(source.replace(" AND c.contype<>''t''", ""));
		}
		var bound = KnownReadySourceParameters.bind(query, sources);
		return new Fixture(db, bound.sql(), old, next, bound.parameters(), knownSources);
	}

	@ParameterizedTest
	@ValueSource(strings = {MES, QMS})
	void acceptsOnlyWholeKnownOldOrUpgradedImplementation(String proc) throws Exception {
		var f = fixture(proc);
		assertEquals(proc.equals(MES) ? 8 : 4, f.knownSources.size(), "每个正式及旧约束签名来源必须独立覆盖");
		for (String source : f.knownSources) {
			f.db.update("UPDATE pg_proc SET prosrc=?", source);
			assertTrue(f.allowed(), source);
		}
		f.db.update("UPDATE pg_proc SET prosrc=prosrc||' -- unknown patch'");
		assertFalse(f.allowed());
	}

	@ParameterizedTest
	@ValueSource(strings = {MES, QMS})
	void retainedDigestCommentsDoNotAuthenticateAnUnknownReady(String proc) throws Exception {
		var f = fixture(proc);
		f.db.update(
				"UPDATE pg_proc SET prosrc=?",
				"BEGIN -- 2ac8edd470a8bed0c752449f6bdd36a7 f33e23323764cf1e1836030ddb8965f2\n"
						+ " RETURN 1;END;");
		assertFalse(f.allowed());
	}

	@ParameterizedTest
	@ValueSource(strings = {MES, QMS})
	void modifiedFunctionAttributesAreRejected(String proc) throws Exception {
		var f = fixture(proc);
		for (String mutation :
				List.of(
						"prosecdef=true",
						"provolatile='i'",
						"proisstrict=true",
						"proconfig=ARRAY['search_path=public']",
						"proparallel='s'",
						"proleakproof=true",
						"proretset=true",
						"pronargs=1",
						"pronargdefaults=1",
						"prorettype='text'",
						"prokind='p'",
						"proallargtypes='integer'",
						"proargmodes='i'")) {
			f.db.execute("UPDATE pg_proc SET " + mutation);
			assertFalse(f.allowed(), mutation);
			f.db.execute(
					"UPDATE pg_proc SET"
						+ " prosecdef=false,provolatile='v',proisstrict=false,proconfig=ARRAY['search_path=pg_catalog,"
						+ " "
							+ proc.split("\\.")[0]
							+ "'],proparallel='u',proleakproof=false,proretset=false,pronargs=0,pronargdefaults=0,prorettype='integer',prokind='f',proallargtypes=null,proargmodes=null");
		}
		f.db.execute("UPDATE pg_language SET lanname='sql'");
		assertFalse(f.allowed());
	}
}
