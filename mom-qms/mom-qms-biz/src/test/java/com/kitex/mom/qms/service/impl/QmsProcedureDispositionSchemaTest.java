package com.kitex.mom.qms.service.impl;

import static org.junit.jupiter.api.Assertions.*;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.*;
import java.time.OffsetDateTime;
import java.util.*;

/** 执行366实际receipt CHECK/唯一性；不将H2替身视为PG守卫或并发认证。 */
class QmsProcedureDispositionSchemaTest {
    JdbcTemplate db;

    @BeforeEach
    void setup() throws Exception {
        String sql =
                Files.readString(
                        Path.of("../../../docs/database/mom-cumulative-upgrade-pg-v4.sql"));
        String marker = "CREATE TEMP TABLE expected_receipt366(";
        int start = sql.indexOf(marker), end = sql.indexOf(") ON COMMIT DROP;", start);
        assertTrue(start > 0 && end > start);
        String ddl =
                sql.substring(start, end + 1)
                        .replace(marker, "CREATE TABLE receipt(")
                        .replace("timestamptz", "timestamp with time zone")
                        .replace("1970-01-01Z", "1970-01-01T00:00:00Z")
                        .replace(
                                "encode(sha256(convert_to(payload,'UTF8')),'hex')",
                                "LOWER(RAWTOHEX(HASH('SHA-256',STRINGTOUTF8(payload))))");
        var ds = new JdbcDataSource();
        ds.setURL(
                "jdbc:h2:mem:disposition_schema_"
                        + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        db = new JdbcTemplate(ds);
        db.execute(ddl);
    }

    Object[] row() {
        return new Object[] {
            1L,
            1L,
            "F1",
            700L,
            "disposition-request-01",
            2L,
            3L,
            4L,
            "{}",
            QmsInspectionEventDeliveryCodec.hash("{}"),
            OffsetDateTime.parse("2026-10-27T00:00:00Z")
        };
    }

    void insert(Object[] row) {
        db.update("INSERT INTO receipt VALUES(?,?,?,?,?,?,?,?,?,?,?)", row);
    }

    @Test
    void actualDefinitionAcceptsCanonicalReceipt() {
        insert(row());
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM receipt", Integer.class));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "ID", "TENANT", "FACTORY", "TASK", "REQUEST", "RECORD", "HISTORY", "EVENT",
                "PAYLOAD", "SHA", "TIME", "NULL"
            })
    void rejectsInvalidReceipt(String bad) {
        var v = row();
        switch (bad) {
            case "ID" -> v[0] = 0L;
            case "TENANT" -> v[1] = 0L;
            case "FACTORY" -> v[2] = " ";
            case "TASK" -> v[3] = 0L;
            case "REQUEST" -> v[4] = "bad id";
            case "RECORD" -> v[5] = 0L;
            case "HISTORY" -> v[6] = 0L;
            case "EVENT" -> v[7] = 0L;
            case "PAYLOAD" -> {
                v[8] = "量".repeat(12000);
                v[9] = QmsInspectionEventDeliveryCodec.hash((String) v[8]);
            }
            case "SHA" -> v[8] = "{ }";
            case "TIME" -> v[10] = OffsetDateTime.parse("1970-01-01T00:00:00Z");
            case "NULL" -> v[8] = null;
            default -> fail(bad);
        }
        assertThrows(DataIntegrityViolationException.class, () -> insert(v));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 4, 5, 6, 7})
    void rejectsReuseOfAnyOriginalIdentity(int same) {
        Object[] a = row(),
                b =
                        {
                            11L,
                            1L,
                            "F1",
                            700L,
                            "disposition-request-02",
                            12L,
                            13L,
                            14L,
                            "{}",
                            QmsInspectionEventDeliveryCodec.hash("{}"),
                            OffsetDateTime.parse("2026-10-27T00:01:00Z")
                        };
        b[same] = a[same];
        insert(a);
        assertThrows(DataIntegrityViolationException.class, () -> insert(b));
    }
}
