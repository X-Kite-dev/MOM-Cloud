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

/** 执行362实际表定义的CHECK/主键/NULL约束；不认证PG守卫、目录或并发锁。 */
class QmsInspectionRevisionSchemaTest {
    JdbcTemplate db;

    @BeforeEach
    void setup() throws Exception {
        var script =
                Files.readString(
                        Path.of(
                                System.getProperty(
                                        "mom.upgrade.file",
                                        "../../../docs/database/mom-cumulative-upgrade-pg-v4.sql")));
        var ds = new JdbcDataSource();
        ds.setURL(
                "jdbc:h2:mem:revision_schema_"
                        + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        db = new JdbcTemplate(ds);
        for (String table : List.of("qms_inspection_revision", "qms_inspection_revision_review")) {
            String marker = "CREATE TEMP TABLE expected_" + table + "_362(";
            int start = script.indexOf(marker), end = script.indexOf(") ON COMMIT DROP;", start);
            assertTrue(start > 0 && end > start);
            String ddl =
                    script.substring(start, end + 1)
                            .replace(marker, "CREATE TABLE " + table + "(")
                            .replace("timestamptz", "timestamp with time zone")
                            .replace(
                                    "encode(sha256(convert_to(payload,'UTF8')),'hex')",
                                    "LOWER(RAWTOHEX(HASH('SHA-256',STRINGTOUTF8(payload))))");
            db.execute(ddl);
        }
    }

    Object[] request() {
        return new Object[] {
            1L,
            1L,
            "F1",
            700L,
            11L,
            2L,
            "a".repeat(64),
            2L,
            OffsetDateTime.parse("2026-10-27T00:00:00Z"),
            "QUALIFIED",
            5,
            5,
            "量具复核",
            "{}",
            QmsInspectionEventDeliveryCodec.hash("{}")
        };
    }

    Object[] review() {
        return new Object[] {
            1L,
            1L,
            "F1",
            700L,
            "a".repeat(64),
            3L,
            OffsetDateTime.parse("2026-10-27T00:10:00Z"),
            "APPROVED",
            "独立审核"
        };
    }

    void insert(String table, Object[] values) {
        db.update(
                "INSERT INTO "
                        + table
                        + " VALUES("
                        + String.join(",", Collections.nCopies(values.length, "?"))
                        + ")",
                values);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "ID",
                "TENANT",
                "FACTORY",
                "TASK",
                "EVENT",
                "SEQ0",
                "SEQ5001",
                "BASE_SHA",
                "ACTOR",
                "RESULT",
                "USABLE",
                "HANDLED",
                "REASON",
                "UTF8",
                "RAW_SHA"
            })
    void actualRequestChecksRejectInvalidIdentityOrOriginal(String bad) {
        var row = request();
        switch (bad) {
            case "ID" -> row[0] = 0L;
            case "TENANT" -> row[1] = 0L;
            case "FACTORY" -> row[2] = " ";
            case "TASK" -> row[3] = 0L;
            case "EVENT" -> row[4] = 0L;
            case "SEQ0" -> row[5] = 0L;
            case "SEQ5001" -> row[5] = 5001L;
            case "BASE_SHA" -> row[6] = "g".repeat(64);
            case "ACTOR" -> row[7] = 0L;
            case "RESULT" -> row[9] = "CANCELED";
            case "USABLE" -> row[10] = -1;
            case "HANDLED" -> row[11] = -1;
            case "REASON" -> row[12] = " ";
            case "UTF8" -> {
                row[13] = "量".repeat(22000);
                row[14] = QmsInspectionEventDeliveryCodec.hash((String) row[13]);
            }
            case "RAW_SHA" -> row[13] = "{ }";
            default -> fail(bad);
        }
        assertThrows(
                org.springframework.dao.DataIntegrityViolationException.class,
                () -> insert("qms_inspection_revision", row));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ID", "TENANT", "FACTORY", "TASK", "SHA", "ACTOR", "DECISION", "NOTE"})
    void actualReviewChecksRejectInvalidIdentityOrOpinion(String bad) {
        var row = review();
        switch (bad) {
            case "ID" -> row[0] = 0L;
            case "TENANT" -> row[1] = 0L;
            case "FACTORY" -> row[2] = " ";
            case "TASK" -> row[3] = 0L;
            case "SHA" -> row[4] = "g".repeat(64);
            case "ACTOR" -> row[5] = 0L;
            case "DECISION" -> row[7] = "APPLIED";
            case "NOTE" -> row[8] = " ";
            default -> fail(bad);
        }
        assertThrows(
                org.springframework.dao.DataIntegrityViolationException.class,
                () -> insert("qms_inspection_revision_review", row));
    }

    @Test
    void exactOnceReviewAndExplicitNullChecks() {
        insert("qms_inspection_revision", request());
        insert("qms_inspection_revision_review", review());
        assertThrows(
                org.springframework.dao.DuplicateKeyException.class,
                () -> insert("qms_inspection_revision_review", review()));
        for (int i = 0; i < request().length; i++) {
            var row = request();
            row[0] = 2L;
            row[i] = null;
            assertThrows(
                    org.springframework.dao.DataIntegrityViolationException.class,
                    () -> insert("qms_inspection_revision", row));
        }
        for (int i = 0; i < review().length; i++) {
            var row = review();
            row[0] = 2L;
            row[i] = null;
            assertThrows(
                    org.springframework.dao.DataIntegrityViolationException.class,
                    () -> insert("qms_inspection_revision_review", row));
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "EXACT",
                "SPOOF_PREFIX",
                "MISSING_CODE",
                "BLANK_CODE",
                "MISSING_FP",
                "BAD_FP",
                "WRONG_PREFIX"
            })
    void actualPgGuardSourcePredicateRequiresExactMesIdentity(String kind) throws Exception {
        var script =
                Files.readString(
                        Path.of(
                                System.getProperty(
                                        "mom.upgrade.file",
                                        "../../../docs/database/mom-cumulative-upgrade-pg-v4.sql")));
        var line =
                script.substring(script.indexOf("-- 362."))
                        .lines()
                        .filter(v -> v.contains("OR e.event_type<>'RESULT'"))
                        .findFirst()
                        .orElseThrow();
        int start = line.indexOf(" OR ", line.indexOf("e.event_type")) + 4,
                end = line.indexOf(" OR t.num", start);
        String predicate =
                line.substring(start, end)
                        .replace("s->>'sourceRequestId'", "request_id")
                        .replace("s->>'sourceTaskCode'", "task_code")
                        .replace("s->>'sourceRequestFingerprint'", "source_sha");
        String id = "MES_QMS_V2:P1:FIRST:10", code = "P1", sha = "a".repeat(64);
        switch (kind) {
            case "SPOOF_PREFIX" -> id = "MESXQMSXV2:P1:FIRST:10";
            case "MISSING_CODE" -> code = null;
            case "BLANK_CODE" -> code = " ";
            case "MISSING_FP" -> sha = null;
            case "BAD_FP" -> sha = "g".repeat(64);
            case "WRONG_PREFIX" -> id = "MANUAL:P1";
            default -> {}
        }
        Boolean rejected =
                db.queryForObject(
                        "SELECT ("
                                + predicate
                                + ") FROM (SELECT CAST(? AS varchar) AS request_id,CAST(? AS"
                                + " varchar) AS task_code,CAST(? AS varchar) AS source_sha) inputs",
                        Boolean.class,
                        id,
                        code,
                        sha);
        assertEquals(!"EXACT".equals(kind), rejected);
    }
}
