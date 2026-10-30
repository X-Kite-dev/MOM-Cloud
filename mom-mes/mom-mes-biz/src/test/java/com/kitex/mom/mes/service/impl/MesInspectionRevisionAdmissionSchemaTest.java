package com.kitex.mom.mes.service.impl;

import static org.junit.jupiter.api.Assertions.*;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.*;
import java.time.OffsetDateTime;
import java.util.*;

/** 执行363真实表定义CHECK/NULL/主键，不认证PG触发器/锁键函数的运行效果。 */
class MesInspectionRevisionAdmissionSchemaTest {
    JdbcTemplate db;

    @BeforeEach
    void setup() throws Exception {
        String script =
                Files.readString(
                        Path.of(
                                System.getProperty(
                                        "mom.upgrade.file",
                                        "../../../docs/database/mom-cumulative-upgrade-pg-v4.sql")));
        var ds = new JdbcDataSource();
        ds.setURL(
                "jdbc:h2:mem:admission_schema_"
                        + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        db = new JdbcTemplate(ds);
        for (String name :
                List.of("mes_inspection_revision_admission", "mes_inspection_revision_cancel")) {
            String marker = "CREATE TEMP TABLE expected_" + name + "_363(";
            int at = script.indexOf(marker), end = script.indexOf(") ON COMMIT DROP;", at);
            assertTrue(at > 0 && end > at);
            String ddl =
                    script.substring(at, end + 1)
                            .replace(marker, "CREATE TABLE " + name + "(")
                            .replace("timestamptz", "timestamp with time zone");
            for (String column : List.of("intent", "basis_snapshot"))
                ddl =
                        ddl.replace(
                                "encode(sha256(convert_to(" + column + ",'UTF8')),'hex')",
                                "LOWER(RAWTOHEX(HASH('SHA-256',STRINGTOUTF8(" + column + "))))");
            db.execute(ddl);
        }
    }

    Object[] admission() {
        return new Object[] {
            1L,
            1L,
            "F1",
            "T1",
            "P1",
            10L,
            700L,
            801L,
            2L,
            "a".repeat(64),
            2L,
            "{}",
            com.kitex.mom.qms.dto.inspection.QmsInspectionRevisionExecutionContract.fingerprint(
                    "{}"),
            "{}",
            com.kitex.mom.qms.dto.inspection.QmsInspectionRevisionExecutionContract.fingerprint(
                    "{}"),
            OffsetDateTime.parse("2026-10-27T00:00:00Z")
        };
    }

    Object[] cancel() {
        return new Object[] {
            1L,
            1L,
            "F1",
            "T1",
            "P1",
            700L,
            "{}",
            com.kitex.mom.qms.dto.inspection.QmsInspectionRevisionExecutionContract.fingerprint(
                    "{}"),
            9L,
            OffsetDateTime.parse("2026-10-27T00:00:00Z"),
            "终止本次准入"
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
                "ROOT",
                "PROCESS",
                "GEN",
                "INSPECTION",
                "EVENT",
                "SEQUENCE",
                "BASE_FP",
                "VERSION",
                "INTENT_SHA",
                "INTENT_BYTES",
                "BASIS_SHA",
                "BASIS_BYTES"
            })
    void actualAdmissionChecksRejectFalseIdentityAndRaw(String bad) {
        var row = admission();
        switch (bad) {
            case "ID" -> row[0] = 0L;
            case "TENANT" -> row[1] = 0L;
            case "FACTORY" -> row[2] = " ";
            case "ROOT" -> row[3] = " ";
            case "PROCESS" -> row[4] = " ";
            case "GEN" -> row[5] = 0L;
            case "INSPECTION" -> row[6] = 0L;
            case "EVENT" -> row[7] = 0L;
            case "SEQUENCE" -> row[8] = 3L;
            case "BASE_FP" -> row[9] = "g".repeat(64);
            case "VERSION" -> row[10] = -1L;
            case "INTENT_SHA" -> row[11] = "{ }";
            case "INTENT_BYTES" -> {
                row[11] = "量".repeat(44000);
                row[12] =
                        com.kitex.mom.qms.dto.inspection.QmsInspectionRevisionExecutionContract
                                .fingerprint((String) row[11]);
            }
            case "BASIS_SHA" -> row[13] = "{ }";
            case "BASIS_BYTES" -> {
                row[13] = "量".repeat(22000);
                row[14] =
                        com.kitex.mom.qms.dto.inspection.QmsInspectionRevisionExecutionContract
                                .fingerprint((String) row[13]);
            }
            default -> fail(bad);
        }
        assertThrows(
                org.springframework.dao.DataIntegrityViolationException.class,
                () -> insert("mes_inspection_revision_admission", row));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "ID",
                "TENANT",
                "FACTORY",
                "ROOT",
                "PROCESS",
                "INSPECTION",
                "SHA",
                "ACTOR",
                "REASON"
            })
    void actualCancellationChecksRejectIncompleteRelease(String bad) {
        var row = cancel();
        switch (bad) {
            case "ID" -> row[0] = 0L;
            case "TENANT" -> row[1] = 0L;
            case "FACTORY" -> row[2] = " ";
            case "ROOT" -> row[3] = " ";
            case "PROCESS" -> row[4] = " ";
            case "INSPECTION" -> row[5] = 0L;
            case "SHA" -> row[6] = "{ }";
            case "ACTOR" -> row[8] = 0L;
            case "REASON" -> row[10] = " ";
            default -> fail(bad);
        }
        assertThrows(
                org.springframework.dao.DataIntegrityViolationException.class,
                () -> insert("mes_inspection_revision_cancel", row));
    }

    @Test
    void allFieldsAreExplicitAndEachOriginalHasOnePermanentIdentity() {
        insert("mes_inspection_revision_admission", admission());
        insert("mes_inspection_revision_cancel", cancel());
        assertThrows(
                org.springframework.dao.DuplicateKeyException.class,
                () -> insert("mes_inspection_revision_admission", admission()));
        assertThrows(
                org.springframework.dao.DuplicateKeyException.class,
                () -> insert("mes_inspection_revision_cancel", cancel()));
        for (int i = 0; i < admission().length; i++) {
            var row = admission();
            row[0] = 2L;
            row[i] = null;
            assertThrows(
                    org.springframework.dao.DataIntegrityViolationException.class,
                    () -> insert("mes_inspection_revision_admission", row));
        }
        for (int i = 0; i < cancel().length; i++) {
            var row = cancel();
            row[0] = 2L;
            row[i] = null;
            assertThrows(
                    org.springframework.dao.DataIntegrityViolationException.class,
                    () -> insert("mes_inspection_revision_cancel", row));
        }
    }
}
