package com.kitex.mom.mes.service.impl;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.*;

/** 执行368实际表CHECK/唯一性；不将H2替身视为PG触发器、ready或并发认证。 */
class MesManualTaskSchemaTest {
    JdbcTemplate db;
    String raw =
            "{\"requestId\":\"manual-request-0001\",\"expectedTenantId\":\"1\",\"expectedFactoryCode\":\"F1\",\"expectedActorId\":\"7\",\"matCode\":\"P1\",\"engineeringReleaseId\":\"901\",\"engineeringContentHash\":\""
                    + "e".repeat(64)
                    + "\",\"quantity\":\"5\",\"type\":\"PRODUCT\",\"planStartDate\":\"2026-10-27"
                    + " 08:00:00\",\"planEndDate\":\"2026-10-28 18:00:00\"}";

    @BeforeEach
    void setup() throws Exception {
        var sql =
                Files.readString(
                        Path.of("../../../docs/database/mom-cumulative-upgrade-pg-v4.sql"));
        var marker = "CREATE TEMP TABLE expected_manual_task_request368(";
        int a = sql.indexOf(marker), b = sql.indexOf(") ON COMMIT DROP;", a);
        assertTrue(a > 0 && b > a);
        String ddl =
                sql.substring(a, b + 1)
                        .replace(marker, "CREATE TABLE request(")
                        .replace("timestamptz", "timestamp with time zone")
                        .replace(
                                "encode(sha256(convert_to(command_payload,'UTF8')),'hex')",
                                "LOWER(RAWTOHEX(HASH('SHA-256',STRINGTOUTF8(command_payload))))");
        db =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                "jdbc:h2:mem:manual_schema_"
                                        + UUID.randomUUID()
                                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                                "sa",
                                ""));
        db.execute(ddl);
    }

    static String hash(String value) throws Exception {
        return HexFormat.of()
                .formatHex(
                        MessageDigest.getInstance("SHA-256")
                                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    Object[] row() throws Exception {
        return new Object[] {
            1L,
            1L,
            "F1",
            "manual-request-0001",
            7L,
            raw,
            hash(raw),
            "ACCEPTED",
            11L,
            "MANUAL-1",
            OffsetDateTime.parse("2026-10-27T00:00:00Z")
        };
    }

    void insert(Object[] v) {
        db.update("INSERT INTO request VALUES(?,?,?,?,?,?,?,?,?,?,?)", v);
    }

    @Test
    void acceptedAndCancelledOriginalsAreBothSupported() throws Exception {
        insert(row());
        var cancel = row();
        cancel[0] = 2L;
        cancel[3] = "manual-request-0002";
        cancel[5] = raw.replace("manual-request-0001", "manual-request-0002");
        cancel[6] = hash((String) cancel[5]);
        cancel[7] = "CANCELLED";
        cancel[8] = null;
        cancel[9] = null;
        insert(cancel);
        assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM request", Integer.class));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "ID",
                "TENANT",
                "FACTORY",
                "REQUEST",
                "ACTOR",
                "SHA",
                "UTF8",
                "STATE",
                "TASK",
                "TASKNULL",
                "CODENULL",
                "CODEBLANK",
                "TIME",
                "CANCEL_TASK"
            })
    void invalidOriginalRejected(String bad) throws Exception {
        var v = row();
        switch (bad) {
            case "ID" -> v[0] = 0L;
            case "TENANT" -> v[1] = 0L;
            case "FACTORY" -> v[2] = " ";
            case "REQUEST" -> v[3] = "bad id";
            case "ACTOR" -> v[4] = 0L;
            case "SHA" -> v[5] = "{ }";
            case "UTF8" -> {
                v[5] = "量".repeat(3000);
                v[6] = hash((String) v[5]);
            }
            case "STATE" -> v[7] = "UNKNOWN";
            case "TASK" -> v[8] = 0L;
            case "TASKNULL" -> v[8] = null;
            case "CODENULL" -> v[9] = null;
            case "CODEBLANK" -> v[9] = " ";
            case "TIME" -> v[10] = OffsetDateTime.parse("1970-01-01T00:00:00Z");
            case "CANCEL_TASK" -> v[7] = "CANCELLED";
            default -> fail(bad);
        }
        assertThrows(DataIntegrityViolationException.class, () -> insert(v));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 3, 8})
    void originalIdentityCannotBeReused(int same) throws Exception {
        var a = row();
        Object[] b = {
            2L,
            1L,
            "F1",
            "manual-request-0002",
            7L,
            raw,
            hash(raw),
            "ACCEPTED",
            12L,
            "MANUAL-2",
            OffsetDateTime.parse("2026-10-27T00:01:00Z")
        };
        b[same] = a[same];
        insert(a);
        assertThrows(DataIntegrityViolationException.class, () -> insert(b));
    }
}
