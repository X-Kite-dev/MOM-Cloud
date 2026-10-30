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

/** 执行364真实DDL约束；不冒充目标PG触发器、升级或并发验收。 */
class QmsInspectionRevisionCoordinationSchemaTest {
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
        var ds = new JdbcDataSource();
        ds.setURL(
                "jdbc:h2:mem:coord_schema_"
                        + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        db = new JdbcTemplate(ds);
        db.execute("CREATE SCHEMA mom_qms");
        for (String kind : List.of("execution", "delivery", "cancel", "receipt")) {
            String table = "qms_inspection_revision_" + kind,
                    marker = "CREATE TEMP TABLE expected_" + table + "_364(";
            int start = script.indexOf(marker), end = script.indexOf(") ON COMMIT DROP;", start);
            assertTrue(start > 0 && end > start, "missing364 " + kind);
            String ddl =
                    script.substring(start, end + 1)
                            .replace(marker, "CREATE TABLE mom_qms." + table + "(")
                            .replace("timestamptz", "timestamp with time zone");
            for (String col : List.of("intent", "payload"))
                ddl =
                        ddl.replace(
                                "encode(sha256(convert_to(" + col + ",'UTF8')),'hex')",
                                "LOWER(RAWTOHEX(HASH('SHA-256',STRINGTOUTF8(" + col + "))))");
            db.execute(ddl);
        }
    }

    Object[] row(String kind) {
        var now = OffsetDateTime.parse("2026-10-27T00:00:00Z");
        var fp =
                com.kitex.mom.qms.dto.inspection.QmsInspectionRevisionExecutionContract.fingerprint(
                        "{}");
        return switch (kind) {
            case "execution" -> new Object[] {1L, 1L, "F1", 700L, "{}", fp, 4L, now};
            case "delivery" ->
                    new Object[] {1L, 1L, "F1", 700L, "RESERVE", "PENDING", 0, now, now, null, 0L};
            case "cancel" -> new Object[] {1L, 1L, "F1", 700L, 4L, now, "取消准备"};
            case "receipt" -> new Object[] {2L, 1L, 1L, "F1", 700L, "CANCEL", "{}", fp, now};
            default -> throw new IllegalArgumentException();
        };
    }

    void insert(String kind, Object[] row) {
        db.update(
                "INSERT INTO mom_qms.qms_inspection_revision_"
                        + kind
                        + " VALUES("
                        + String.join(",", Collections.nCopies(row.length, "?"))
                        + ")",
                row);
    }

    @ParameterizedTest
    @ValueSource(strings = {"execution", "delivery", "cancel", "receipt"})
    void positiveRowsAndPrimaryKeysAreRealConstraints(String kind) {
        insert(kind, row(kind));
        assertThrows(RuntimeException.class, () -> insert(kind, row(kind)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"execution", "delivery", "cancel", "receipt"})
    void requiredColumnsCannotBeNull(String kind) {
        var original = row(kind);
        for (int i = 0; i < original.length; i++) {
            if ("delivery".equals(kind) && i == 9) continue;
            var changed = original.clone();
            changed[i] = null;
            assertThrows(RuntimeException.class, () -> insert(kind, changed), kind + " column" + i);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ID", "TENANT", "FACTORY", "TASK", "EXECUTOR", "HASH", "BYTES"})
    void executionRefusesInvalidAndOversizedFacts(String bad) {
        var row = row("execution");
        switch (bad) {
            case "ID" -> row[0] = 0L;
            case "TENANT" -> row[1] = 0L;
            case "FACTORY" -> row[2] = " ";
            case "TASK" -> row[3] = 0L;
            case "EXECUTOR" -> row[6] = 0L;
            case "HASH" -> row[5] = "b".repeat(64);
            case "BYTES" -> {
                row[4] = "中".repeat(50000);
                row[5] =
                        com.kitex.mom.qms.dto.inspection.QmsInspectionRevisionExecutionContract
                                .fingerprint((String) row[4]);
            }
        }
        assertThrows(RuntimeException.class, () -> insert("execution", row));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "OPERATION",
                "STATUS",
                "ATTEMPTS",
                "TERMINAL_DUE",
                "ATTENTION_REASON",
                "BAD_VERSION"
            })
    void deliveryHasExactStatePredicates(String bad) {
        var row = row("delivery");
        switch (bad) {
            case "OPERATION" -> row[4] = "COMMIT";
            case "STATUS" -> row[5] = "APPLIED";
            case "ATTEMPTS" -> row[6] = 9;
            case "TERMINAL_DUE" -> row[5] = "RESERVED";
            case "ATTENTION_REASON" -> {
                row[5] = "ATTENTION";
                row[7] = null;
            }
            case "BAD_VERSION" -> row[10] = -1L;
        }
        assertThrows(RuntimeException.class, () -> insert("delivery", row));
    }

    @ParameterizedTest
    @ValueSource(strings = {"REASON_EMPTY", "REASON_LONG", "ACTOR", "RECEIPT_HASH", "RECEIPT_OP"})
    void cancellationAndReceiptFactsAreBounded(String bad) {
        String kind = bad.startsWith("RECEIPT") ? "receipt" : "cancel";
        var row = row(kind);
        switch (bad) {
            case "REASON_EMPTY" -> row[6] = " ";
            case "REASON_LONG" -> row[6] = "x".repeat(1001);
            case "ACTOR" -> row[4] = 0L;
            case "RECEIPT_HASH" -> row[7] = "b".repeat(64);
            case "RECEIPT_OP" -> row[5] = "STATUS";
        }
        assertThrows(RuntimeException.class, () -> insert(kind, row));
    }

    @Test
    void actualCancellationPredicateRequiresFullSameScopeTerminal() {
        db.execute(
                "CREATE TABLE mom_qms.qms_inspection_revision(id bigint,tenant_id"
                        + " bigint,factory_code varchar,task_id bigint)");
        db.update("INSERT INTO mom_qms.qms_inspection_revision VALUES(1,1,'F1',700)");
        insert("execution", row("execution"));
        insert("delivery", row("delivery"));
        insert("receipt", row("receipt"));
        String query =
                "SELECT COUNT(*) FROM mom_qms.qms_inspection_revision r WHERE EXISTS("
                        + com.kitex.mom.qms.mapper.QmsInspectionRevisionMapper
                                .CONFIRMED_CANCELLATION
                        + ")";
        for (String col : List.of("z.payload", "x.intent"))
            query =
                    query.replace(
                            "encode(sha256(convert_to(" + col + ",'UTF8')),'hex')",
                            "LOWER(RAWTOHEX(HASH('SHA-256',STRINGTOUTF8(" + col + "))))");
        assertEquals(0, db.queryForObject(query, Integer.class));
        insert("cancel", row("cancel"));
        assertEquals(0, db.queryForObject(query, Integer.class));
        db.update(
                "UPDATE mom_qms.qms_inspection_revision_delivery SET"
                    + " operation='CANCEL',status='CANCELED',attempt_count=1,next_attempt_at=NULL");
        assertEquals(1, db.queryForObject(query, Integer.class));
        db.update("UPDATE mom_qms.qms_inspection_revision_cancel SET factory_code='OTHER'");
        assertEquals(0, db.queryForObject(query, Integer.class));
    }
}
