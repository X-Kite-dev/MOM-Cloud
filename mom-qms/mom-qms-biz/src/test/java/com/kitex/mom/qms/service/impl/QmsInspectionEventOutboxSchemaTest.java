package com.kitex.mom.qms.service.impl;

import static org.junit.jupiter.api.Assertions.*;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.*;
import java.time.OffsetDateTime;
import java.util.*;

/** 执行358真实表CHECK/唯一及目录谓词；不执行或认证PG trigger和锁。 */
class QmsInspectionEventOutboxSchemaTest {
    JdbcTemplate db;
    String script;

    @BeforeEach
    void setup() throws Exception {
        script =
                Files.readString(
                        Path.of(
                                System.getProperty(
                                        "mom.upgrade.file",
                                        "../../../docs/database/mom-cumulative-upgrade-pg-v4.sql")));
        int start = script.indexOf("CREATE TEMP TABLE mom_qms_inspection_outbox_expected_358 (");
        int end = script.indexOf(") ON COMMIT DROP;", start);
        assertTrue(start > 0 && end > start);
        String ddl =
                script.substring(start, end + 1)
                        .replace(
                                "CREATE TEMP TABLE mom_qms_inspection_outbox_expected_358",
                                "CREATE TABLE ordinary_outbox_probe")
                        .replace("timestamptz", "timestamp with time zone");
        var ds = new JdbcDataSource();
        ds.setURL(
                "jdbc:h2:mem:ordinary_outbox_schema_"
                        + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        db = new JdbcTemplate(ds);
        db.execute(ddl);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "ID",
                "TENANT",
                "FACTORY",
                "TASK",
                "ZERO_SEQ",
                "OVER_SEQ",
                "SHA",
                "STATUS",
                "NEGATIVE_ATTEMPT",
                "PENDING_ATTEMPT",
                "PENDING_RECEIPT",
                "RETRY_DATE",
                "RETRY_ATTEMPT",
                "RETRY_ERROR",
                "ATTENTION_DATE",
                "ATTENTION_ATTEMPT",
                "ATTENTION_ERROR",
                "RECEIVED_LOCAL_TIME",
                "RECEIVED_MES_TIME",
                "RECEIVED_ATTEMPT",
                "RECEIVED_ERROR",
                "RECEIVED_DUE"
            })
    void actualCheckRejectsContradictoryProgressOrIdentity(String bad) {
        Object[] row = row("PENDING");
        switch (bad) {
            case "ID" -> row[0] = 0L;
            case "TENANT" -> row[1] = 0L;
            case "FACTORY" -> row[2] = " ";
            case "TASK" -> row[3] = 0L;
            case "ZERO_SEQ" -> row[4] = 0L;
            case "OVER_SEQ" -> row[4] = 5001L;
            case "SHA" -> row[5] = "g".repeat(64);
            case "STATUS" -> row[6] = "DELIVERED";
            case "NEGATIVE_ATTEMPT" -> row[7] = -1;
            case "PENDING_ATTEMPT" -> row[7] = 1;
            case "PENDING_RECEIPT" -> row[10] = time();
            case "RETRY_DATE" -> {
                row = row("RETRY_WAIT");
                row[8] = null;
            }
            case "RETRY_ATTEMPT" -> {
                row = row("RETRY_WAIT");
                row[7] = 0;
            }
            case "RETRY_ERROR" -> {
                row = row("RETRY_WAIT");
                row[12] = null;
            }
            case "ATTENTION_DATE" -> {
                row = row("ATTENTION");
                row[8] = time();
            }
            case "ATTENTION_ATTEMPT" -> {
                row = row("ATTENTION");
                row[7] = 0;
            }
            case "ATTENTION_ERROR" -> {
                row = row("ATTENTION");
                row[12] = null;
            }
            case "RECEIVED_LOCAL_TIME" -> {
                row = row("RECEIVED");
                row[10] = null;
            }
            case "RECEIVED_MES_TIME" -> {
                row = row("RECEIVED");
                row[11] = null;
            }
            case "RECEIVED_ATTEMPT" -> {
                row = row("RECEIVED");
                row[7] = 0;
            }
            case "RECEIVED_ERROR" -> {
                row = row("RECEIVED");
                row[12] = "error";
            }
            case "RECEIVED_DUE" -> {
                row = row("RECEIVED");
                row[8] = time();
            }
            default -> fail(bad);
        }
        var invalid = row;
        assertThrows(
                org.springframework.dao.DataIntegrityViolationException.class,
                () -> insert(invalid));
        assertEquals(
                0, db.queryForObject("SELECT COUNT(*) FROM ordinary_outbox_probe", Integer.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "RETRY_WAIT", "ATTENTION", "RECEIVED"})
    void allFourActualStatesHaveCoherentReceipts(String state) {
        insert(row(state));
        assertEquals(
                1, db.queryForObject("SELECT COUNT(*) FROM ordinary_outbox_probe", Integer.class));
    }

    @Test
    void eventIdAndPerOrganizationTaskSequenceAreUnique() {
        insert(row("PENDING"));
        var same = row("PENDING");
        same[0] = 9002L;
        assertThrows(org.springframework.dao.DuplicateKeyException.class, () -> insert(same));
        var other = row("PENDING");
        other[0] = 9003L;
        other[1] = 2L;
        insert(other);
        assertEquals(
                2, db.queryForObject("SELECT COUNT(*) FROM ordinary_outbox_probe", Integer.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"INSTALL", "READY"})
    void fullAlwaysUpdateGuardCannotBeNarrowedToOneColumn(String phase) {
        db.execute(
                "CREATE TABLE pg_trigger(tgrelid bigint,tgisinternal boolean,tgname varchar,tgfoid"
                        + " varchar,tgenabled varchar,tgnargs int,tgqual varchar,tgtype int,tgattr"
                        + " varchar)");
        db.update(
                "INSERT INTO pg_trigger"
                    + " VALUES(42,FALSE,'trg_qms_inspection_outbox_row_358','guard','A',0,NULL,31,'')");
        db.update(
                "INSERT INTO pg_trigger"
                    + " VALUES(42,FALSE,'trg_qms_inspection_outbox_truncate_358','guard','A',0,NULL,34,'')");
        assertFalse(rejected(phase));
        db.update(
                "UPDATE pg_trigger SET tgattr='7' WHERE"
                        + " tgname='trg_qms_inspection_outbox_row_358'");
        assertTrue(rejected(phase));
    }

    boolean rejected(String phase) {
        int section = script.indexOf("-- 358.");
        String start =
                "INSTALL".equals(phase)
                        ? " IF EXISTS(SELECT 1 FROM pg_trigger WHERE tgrelid=target AND NOT"
                                + " tgisinternal"
                        : " IF (SELECT count(*) FROM pg_trigger WHERE tgrelid=target AND NOT"
                                + " tgisinternal)<>2";
        String ending =
                "INSTALL".equals(phase)
                        ? " THEN\n  RAISE EXCEPTION '358事件同名"
                        : " THEN RETURN 0; END IF;";
        int a = script.indexOf(start, section);
        int z = script.indexOf(ending, a);
        assertTrue(a > section && z > a);
        String predicate =
                script.substring(a + " IF ".length(), z)
                        .replace("tgrelid=target", "tgrelid=42")
                        .replace(
                                "'mom_qms.guard_inspection_event_outbox_v1()'::regprocedure",
                                "'guard'")
                        .replace(
                                "to_regprocedure('mom_qms.guard_inspection_event_outbox_v1()')",
                                "'guard'")
                        .replace("::int2vector", "");
        return db.queryForObject(
                        "SELECT CASE WHEN (" + predicate + ") THEN 1 ELSE 0 END", Integer.class)
                == 1;
    }

    static OffsetDateTime time() {
        return OffsetDateTime.parse("2026-10-26T00:00:00Z");
    }

    Object[] row(String state) {
        boolean received = state.equals("RECEIVED"),
                pending = state.equals("PENDING"),
                attention = state.equals("ATTENTION");
        return new Object[] {
            9001L,
            1L,
            "F1",
            700L,
            1L,
            "a".repeat(64),
            state,
            pending ? 0 : 1,
            (received || attention) ? null : time(),
            time(),
            received ? time() : null,
            received ? time() : null,
            (pending || received) ? null : "generic failure"
        };
    }

    void insert(Object[] row) {
        db.update("INSERT INTO ordinary_outbox_probe VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)", row);
    }
}
