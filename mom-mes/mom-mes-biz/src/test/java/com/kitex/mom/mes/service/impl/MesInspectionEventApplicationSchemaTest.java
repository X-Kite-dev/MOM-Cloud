package com.kitex.mom.mes.service.impl;

import static org.junit.jupiter.api.Assertions.*;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.UUID;

/** 执行360原始DDL和守卫目录谓词；不认证PG触发器、并发锁或实际安装。 */
class MesInspectionEventApplicationSchemaTest {
    JdbcTemplate db;
    String script;
    static final OffsetDateTime TIME = OffsetDateTime.parse("2026-10-26T12:00:00Z");

    @BeforeEach
    void setup() throws Exception {
        script =
                Files.readString(
                        Path.of(
                                System.getProperty(
                                        "mom.upgrade.file",
                                        "../../../docs/database/mom-cumulative-upgrade-pg-v4.sql")));
        script = script.substring(script.indexOf("-- 360."));
        var ds = new JdbcDataSource();
        ds.setURL(
                "jdbc:h2:mem:application_schema_"
                        + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        db = new JdbcTemplate(ds);
        ddl("state");
        ddl("application");
    }

    void ddl(String name) {
        String table = "mom_mes_event_" + name + "_expected_360";
        int start = script.indexOf("CREATE TEMP TABLE " + table + " (");
        int end = script.indexOf(") ON COMMIT DROP;", start);
        assertTrue(start >= 0 && end > start);
        db.execute(
                script.substring(start, end + 1)
                        .replace("CREATE TEMP TABLE " + table, "CREATE TABLE " + name + "_probe")
                        .replace("timestamptz", "timestamp with time zone")
                        .replace(
                                "encode(sha256(convert_to(decision_snapshot,'UTF8')),'hex')",
                                "LOWER(RAWTOHEX(HASH('SHA-256',STRINGTOUTF8(decision_snapshot))))"));
    }

    Object[] state() {
        return new Object[] {
            10L, 1L, "F1", 700L, "T1", "P1", 0L, null, null, "ACTIVE", null, null, 0L, TIME, TIME
        };
    }

    Object[] application() throws Exception {
        return new Object[] {
            9700L,
            1L,
            "F1",
            10L,
            700L,
            1L,
            "a".repeat(64),
            null,
            null,
            "{}",
            MesInspectionEventApplicationTransactionTest.hash("{}"),
            TIME
        };
    }

    void insert(String table, Object[] row) {
        db.update(
                "INSERT INTO "
                        + table
                        + "_probe VALUES("
                        + String.join(",", java.util.Collections.nCopies(row.length, "?"))
                        + ")",
                row);
    }

    void planDdl() {
        String table = "mom_mes_source_plan_expected_361";
        int start = script.indexOf("CREATE TEMP TABLE " + table + " (");
        int end = script.indexOf(") ON COMMIT DROP;", start);
        assertTrue(start >= 0 && end > start);
        db.execute(
                script.substring(start, end + 1)
                        .replace("CREATE TEMP TABLE " + table, "CREATE TABLE plan_probe")
                        .replace("timestamptz", "timestamp with time zone")
                        .replace(
                                "encode(sha256(convert_to(payload,'UTF8')),'hex')",
                                "LOWER(RAWTOHEX(HASH('SHA-256',STRINGTOUTF8(payload))))"));
    }

    Object[] planRow() throws Exception {
        return new Object[] {
            1L,
            1L,
            "F1",
            "QUALITY-REJECTED:100:3",
            "a".repeat(64),
            1L,
            "T1",
            "{}",
            MesInspectionEventApplicationTransactionTest.hash("{}"),
            "PENDING",
            0L,
            TIME,
            TIME,
            null,
            null
        };
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "ID",
                "TENANT",
                "FACTORY",
                "REQUEST",
                "REQUEST_SHA",
                "TASK",
                "TASK_CODE",
                "RAW_CHANGED",
                "UTF8_LIMIT",
                "STATUS",
                "PENDING_ATTEMPT",
                "RETRY_ERROR",
                "RECEIVED_TIME"
            })
    void actualPlanOutboxConstraintsRejectInvalidOriginalOrProgress(String bad) throws Exception {
        planDdl();
        Object[] row = planRow();
        switch (bad) {
            case "ID" -> row[0] = 0L;
            case "TENANT" -> row[1] = 0L;
            case "FACTORY" -> row[2] = " ";
            case "REQUEST" -> row[3] = " ";
            case "REQUEST_SHA" -> row[4] = "G".repeat(64);
            case "TASK" -> row[5] = 0L;
            case "TASK_CODE" -> row[6] = " ";
            case "RAW_CHANGED" -> row[7] = "{\"changed\":true}";
            case "UTF8_LIMIT" -> {
                row[7] = "浇".repeat(349526);
                row[8] = MesInspectionEventApplicationTransactionTest.hash((String) row[7]);
            }
            case "STATUS" -> row[9] = "APPLIED";
            case "PENDING_ATTEMPT" -> row[10] = 1L;
            case "RETRY_ERROR" -> {
                row[9] = "RETRY_WAIT";
                row[10] = 1L;
            }
            case "RECEIVED_TIME" -> {
                row[9] = "RECEIVED";
                row[10] = 1L;
                row[11] = null;
            }
            default -> throw new AssertionError(bad);
        }
        assertThrows(DataIntegrityViolationException.class, () -> insert("plan", row));
    }

    @Test
    void actualPlanOutboxAllowsRecoverableProgressAndRejectsDuplicateRequest() throws Exception {
        planDdl();
        insert("plan", planRow());
        var duplicate = planRow();
        duplicate[0] = 2L;
        assertThrows(DataIntegrityViolationException.class, () -> insert("plan", duplicate));
        duplicate[1] = 2L;
        duplicate[9] = "RETRY_WAIT";
        duplicate[10] = 1L;
        duplicate[14] = "unknown response";
        insert("plan", duplicate);
        var received = planRow();
        received[0] = 3L;
        received[1] = 3L;
        received[9] = "RECEIVED";
        received[10] = 2L;
        received[11] = null;
        received[13] = TIME;
        insert("plan", received);
    }

    @ParameterizedTest
    @ValueSource(strings = {"INSTALL", "READY"})
    void actualPlanTriggerCatalogRejectsNarrowedDisabledAndForeignGuards(String phase) {
        db.execute(
                "CREATE TABLE pg_trigger(tgrelid bigint,tgisinternal boolean,tgname varchar,tgfoid"
                        + " varchar,tgenabled varchar,tgnargs int,tgqual varchar,tgtype int,tgattr"
                        + " varchar)");
        db.update(
                "INSERT INTO pg_trigger"
                    + " VALUES(42,FALSE,'trg_mes_source_plan_row_361','guard','A',0,NULL,31,'')");
        db.update(
                "INSERT INTO pg_trigger"
                    + " VALUES(42,FALSE,'trg_mes_source_plan_truncate_361','guard','A',0,NULL,34,'')");
        assertFalse(planTriggerRejected(phase));
        db.update("UPDATE pg_trigger SET tgattr='7' WHERE tgtype=31");
        assertTrue(planTriggerRejected(phase));
        db.update("UPDATE pg_trigger SET tgattr='',tgenabled='O' WHERE tgtype=31");
        assertTrue(planTriggerRejected(phase));
        db.update("UPDATE pg_trigger SET tgenabled='A' WHERE tgtype=31");
        db.update("INSERT INTO pg_trigger VALUES(42,FALSE,'foreign','other','A',0,NULL,31,'')");
        assertTrue(planTriggerRejected(phase));
    }

    boolean planTriggerRejected(String phase) {
        int section = script.indexOf("-- 361.");
        int start =
                script.indexOf(
                        phase.equals("INSTALL")
                                ? " IF EXISTS(SELECT 1 FROM pg_trigger WHERE tgrelid=target"
                                : " IF (SELECT count(*) FROM pg_trigger WHERE tgrelid=target",
                        section);
        int end =
                script.indexOf(
                        phase.equals("INSTALL")
                                ? " THEN RAISE EXCEPTION '361未知或缩窄"
                                : " THEN RETURN 0; END IF;",
                        start);
        assertTrue(start > section && end > start);
        var predicate =
                script.substring(start + " IF ".length(), end)
                        .replace("tgrelid=target", "tgrelid=42")
                        .replace(
                                "'mom_mes.guard_source_quantity_plan_outbox_v1()'::regprocedure",
                                "'guard'")
                        .replace(
                                "to_regprocedure('mom_mes.guard_source_quantity_plan_outbox_v1()')",
                                "'guard'")
                        .replace("::int2vector", "");
        return db.queryForObject(
                        "SELECT CASE WHEN (" + predicate + ") THEN 1 ELSE 0 END", Integer.class)
                == 1;
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "ID",
                "TENANT",
                "FACTORY",
                "INSPECTION",
                "ROOT",
                "PROCESS",
                "SEQUENCE_NEGATIVE",
                "SEQUENCE_LIMIT",
                "VERSION",
                "INITIAL_POINTER",
                "ADVANCED_POINTER",
                "STATUS",
                "ACTIVE_ERROR",
                "CONFLICT_ERROR",
                "CONFLICT_BLOCKER",
                "TIME"
            })
    void stateConstraintsRejectInvalidCursors(String invalid) {
        Object[] r = state();
        switch (invalid) {
            case "ID" -> r[0] = 0L;
            case "TENANT" -> r[1] = 0L;
            case "FACTORY" -> r[2] = " ";
            case "INSPECTION" -> r[3] = 0L;
            case "ROOT" -> r[4] = " ";
            case "PROCESS" -> r[5] = " ";
            case "SEQUENCE_NEGATIVE" -> r[6] = -1L;
            case "SEQUENCE_LIMIT" -> {
                r[6] = 5001L;
                r[7] = 9700L;
                r[8] = "a".repeat(64);
                r[12] = 5001L;
            }
            case "VERSION" -> r[12] = -1L;
            case "INITIAL_POINTER" -> r[7] = 9700L;
            case "ADVANCED_POINTER" -> {
                r[6] = 1L;
                r[12] = 1L;
            }
            case "STATUS" -> r[9] = "SKIPPED";
            case "ACTIVE_ERROR" -> r[11] = "failed";
            case "CONFLICT_ERROR" -> r[9] = "CONFLICT";
            case "CONFLICT_BLOCKER" -> {
                r[9] = "CONFLICT";
                r[10] = 0L;
                r[11] = "failed";
            }
            case "TIME" -> r[14] = null;
            default -> throw new AssertionError(invalid);
        }
        assertThrows(DataIntegrityViolationException.class, () -> insert("state", r));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "ID",
                "TENANT",
                "FACTORY",
                "GEN",
                "INSPECTION",
                "SEQUENCE_ZERO",
                "SEQUENCE_LIMIT",
                "EVENT_SHA",
                "INITIAL_POINTER",
                "NEXT_POINTER",
                "RAW_CHANGED",
                "DECISION_SHA",
                "UTF8_LIMIT",
                "TIME"
            })
    void applicationConstraintsRejectChangedOriginalsAndInvalidLinks(String invalid)
            throws Exception {
        Object[] r = application();
        switch (invalid) {
            case "ID" -> r[0] = 0L;
            case "TENANT" -> r[1] = 0L;
            case "FACTORY" -> r[2] = " ";
            case "GEN" -> r[3] = 0L;
            case "INSPECTION" -> r[4] = 0L;
            case "SEQUENCE_ZERO" -> r[5] = 0L;
            case "SEQUENCE_LIMIT" -> {
                r[5] = 5001L;
                r[7] = 9699L;
                r[8] = "a".repeat(64);
            }
            case "EVENT_SHA" -> r[6] = "G".repeat(64);
            case "INITIAL_POINTER" -> r[7] = 9699L;
            case "NEXT_POINTER" -> r[5] = 2L;
            case "RAW_CHANGED" -> r[9] = "{\"changed\":true}";
            case "DECISION_SHA" -> r[10] = "g".repeat(64);
            case "UTF8_LIMIT" -> {
                r[9] = "浇".repeat(349526);
                r[10] = MesInspectionEventApplicationTransactionTest.hash((String) r[9]);
            }
            case "TIME" -> r[11] = null;
            default -> throw new AssertionError(invalid);
        }
        assertThrows(DataIntegrityViolationException.class, () -> insert("application", r));
    }

    @Test
    void validLinksConflictAndOrganizationKeysUseTheActualDdl() throws Exception {
        insert("state", state());
        Object[] advanced = state();
        advanced[0] = 20L;
        advanced[3] = 800L;
        advanced[6] = 1L;
        advanced[7] = 9701L;
        advanced[8] = "a".repeat(64);
        advanced[12] = 1L;
        insert("state", advanced);
        Object[] conflict = state();
        conflict[0] = 30L;
        conflict[3] = 900L;
        conflict[9] = "CONFLICT";
        conflict[11] = "legacy requires audit";
        insert("state", conflict);
        Object[] duplicate = state();
        duplicate[0] = 40L;
        assertThrows(DataIntegrityViolationException.class, () -> insert("state", duplicate));
        insert("application", application());
        Object[] same = application();
        same[0] = 9701L;
        assertThrows(DataIntegrityViolationException.class, () -> insert("application", same));
        same[1] = 2L;
        insert("application", same);
        Object[] next = application();
        next[0] = 9702L;
        next[5] = 2L;
        next[7] = 9700L;
        next[8] = "a".repeat(64);
        insert("application", next);
        assertEquals(3, db.queryForObject("SELECT COUNT(*) FROM application_probe", Integer.class));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "INSTALL_STATE",
                "READY_STATE",
                "INSTALL_APPLICATION",
                "READY_APPLICATION",
                "INSTALL_MANAGED",
                "READY_MANAGED"
            })
    void catalogChecksRejectDisabledNarrowedAndUnexpectedGuards(String phase) {
        String prefix = phase.substring(phase.indexOf('_') + 1).toLowerCase();
        String row = "trg_mes_event_" + prefix + "_row_360",
                truncate = "trg_mes_event_" + prefix + "_truncate_360";
        db.execute(
                "CREATE TABLE pg_trigger(tgrelid bigint,tgisinternal boolean,tgname varchar,tgfoid"
                        + " varchar,tgenabled varchar,tgnargs int,tgqual varchar,tgtype int,tgattr"
                        + " varchar)");
        db.update("INSERT INTO pg_trigger VALUES(42,FALSE,?,'guard','A',0,NULL,31,'')", row);
        db.update("INSERT INTO pg_trigger VALUES(42,FALSE,?,'guard','A',0,NULL,34,'')", truncate);
        assertFalse(rejected(phase, prefix, row, truncate));
        db.update("UPDATE pg_trigger SET tgattr='7' WHERE tgname=?", row);
        assertTrue(rejected(phase, prefix, row, truncate));
        db.update("UPDATE pg_trigger SET tgattr='',tgenabled='O' WHERE tgname=?", row);
        assertTrue(rejected(phase, prefix, row, truncate));
        db.update("UPDATE pg_trigger SET tgenabled='A' WHERE tgname=?", row);
        db.update("INSERT INTO pg_trigger VALUES(42,FALSE,'unrelated','other','A',0,NULL,31,'')");
        assertEquals(!prefix.equals("managed"), rejected(phase, prefix, row, truncate));
    }

    boolean rejected(String phase, String prefix, String row, String truncate) {
        String begin =
                phase.startsWith("INSTALL")
                        ? "  IF EXISTS(SELECT 1 FROM pg_trigger WHERE tgrelid=target AND NOT"
                                + " tgisinternal"
                        : "  IF (SELECT count(*) FROM pg_trigger WHERE tgrelid=target AND NOT"
                                + " tgisinternal AND (prefix";
        int start = script.indexOf(begin),
                end =
                        script.indexOf(
                                phase.startsWith("INSTALL")
                                        ? " THEN RAISE EXCEPTION '360未知或缩窄"
                                        : " THEN RETURN 0; END IF;",
                                start);
        assertTrue(start >= 0 && end > start);
        String predicate =
                script.substring(start + "  IF ".length(), end)
                        .replace("tgrelid=target", "tgrelid=42")
                        .replace(
                                "'mom_mes.guard_inspection_event_application_v1()'::regprocedure",
                                "'guard'")
                        .replace(
                                "to_regprocedure('mom_mes.guard_inspection_event_application_v1()')",
                                "'guard'")
                        .replace("::int2vector", "")
                        .replace("truncate_name", "'" + truncate + "'")
                        .replace("row_name", "'" + row + "'")
                        .replace("prefix", "'" + prefix + "'");
        return db.queryForObject(
                        "SELECT CASE WHEN (" + predicate + ") THEN 1 ELSE 0 END", Integer.class)
                == 1;
    }
}
