package com.kitex.mom.mes.service.impl;

import static org.junit.jupiter.api.Assertions.*;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;

/** 执行357实际表定义的CHECK/唯一约束；H2不执行或认证PG守卫及目录核验。 */
class MesInspectionEventInboxSchemaTest {
    private JdbcTemplate db;
    private String script;

    @BeforeEach
    void setup() throws Exception {
        script =
                Files.readString(
                        Path.of(
                                System.getProperty(
                                        "mom.upgrade.file",
                                        "../../../docs/database/mom-cumulative-upgrade-pg-v4.sql")));
        int start = script.indexOf("CREATE TEMP TABLE mom_mes_inbox_expected_357 (");
        int end = script.indexOf(") ON COMMIT DROP;", start);
        assertTrue(start > 0 && end > start, "必须验证真实357定义，不能用残留或镜像DDL");
        String ddl =
                script.substring(start, end + 1)
                        .replace(
                                "CREATE TEMP TABLE mom_mes_inbox_expected_357",
                                "CREATE TABLE mes_inbox_probe")
                        .replace("timestamptz", "timestamp with time zone")
                        .replace(
                                "encode(sha256(convert_to(payload,'UTF8')),'hex')",
                                "LOWER(RAWTOHEX(HASH('SHA-256',STRINGTOUTF8(payload))))");
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:inbox_schema_" + UUID.randomUUID() + ";MODE=PostgreSQL");
        db = new JdbcTemplate(ds);
        // JdbcTemplate每次独立连接；测试期间保留此私有内存库。
        ds.setURL(ds.getURL() + ";DB_CLOSE_DELAY=-1");
        db.execute(ddl);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "ID",
                "GEN_RECORD",
                "RECEIVED_TIME",
                "TENANT",
                "FACTORY",
                "TYPE",
                "TASK",
                "HISTORY",
                "ACTION",
                "SEQUENCE_ZERO",
                "SEQUENCE_LIMIT",
                "FIRST_HAS_PREVIOUS",
                "NEXT_MISSING_PREVIOUS",
                "FINGERPRINT",
                "RAW_CHANGED",
                "UTF8_LIMIT"
            })
    void theActualCheckRejectsInvalidEventHeadersAndRawBytes(String invalid) throws Exception {
        Object[] row = row(1L, 1L, 1L, 1L);
        switch (invalid) {
            case "ID" -> row[0] = 0L;
            case "GEN_RECORD" -> row[13] = 0L;
            case "RECEIVED_TIME" -> row[14] = null;
            case "TENANT" -> row[1] = 0L;
            case "FACTORY" -> row[2] = " ";
            case "TYPE" -> row[3] = "UNKNOWN";
            case "TASK" -> row[4] = 0L;
            case "HISTORY" -> row[5] = 0L;
            case "ACTION" -> row[6] = "RELEASE";
            case "SEQUENCE_ZERO" -> row[7] = 0L;
            case "SEQUENCE_LIMIT" -> row[7] = 5001L;
            case "FIRST_HAS_PREVIOUS" -> {
                row[8] = 9L;
                row[9] = "a".repeat(64);
            }
            case "NEXT_MISSING_PREVIOUS" -> row[7] = 2L;
            case "FINGERPRINT" -> row[12] = "G".repeat(64);
            case "RAW_CHANGED" -> row[11] = "{\"different\":true}";
            case "UTF8_LIMIT" -> {
                row[11] = "浇".repeat(22000);
                row[12] = hash((String) row[11]);
            }
            default -> throw new AssertionError(invalid);
        }
        assertThrows(
                org.springframework.dao.DataIntegrityViolationException.class, () -> insert(row));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM mes_inbox_probe", Integer.class));
    }

    @Test
    void validInitialAndLinkedHeadersPassTheActualCheck() throws Exception {
        insert(row(1L, 1L, 1L, 1L));
        Object[] next = row(2L, 1L, 2L, 2L);
        next[8] = 1L;
        next[9] = hash("{}");
        insert(next);
        assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM mes_inbox_probe", Integer.class));
    }

    @Test
    void theSameSourceHistoryAndSequenceCannotBeClaimedTwice() throws Exception {
        insert(row(1L, 1L, 1L, 1L));
        Object[] sameHistory = row(2L, 1L, 1L, 2L);
        sameHistory[8] = 1L;
        sameHistory[9] = hash("{}");
        assertThrows(
                org.springframework.dao.DuplicateKeyException.class, () -> insert(sameHistory));
        assertThrows(
                org.springframework.dao.DuplicateKeyException.class,
                () -> insert(row(3L, 1L, 3L, 1L)));
        insert(row(4L, 2L, 1L, 1L));
        assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM mes_inbox_probe", Integer.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"INSTALL", "READY"})
    void columnRestrictedTriggersCannotMasqueradeAsTheFullUpdateGuard(String phase) {
        db.execute(
                "CREATE TABLE pg_trigger(tgrelid bigint,tgisinternal boolean,tgname varchar,tgfoid"
                        + " varchar,tgenabled varchar,tgnargs int,tgqual varchar,tgtype int,tgattr"
                        + " varchar)");
        db.update(
                "INSERT INTO pg_trigger"
                        + " VALUES(42,FALSE,'trg_mes_inbox_row_357','guard','A',0,NULL,31,'')");
        db.update(
                "INSERT INTO pg_trigger"
                    + " VALUES(42,FALSE,'trg_mes_inbox_truncate_357','guard','A',0,NULL,34,'')");
        assertFalse(rejectsTriggerMetadata(phase), "完整ALWAYS守卫应被接受");
        db.update("UPDATE pg_trigger SET tgattr='7' WHERE tgname='trg_mes_inbox_row_357'");
        assertTrue(rejectsTriggerMetadata(phase), "UPDATE OF缩窄守卫必须被拒绝");
    }

    private boolean rejectsTriggerMetadata(String phase) {
        int section = script.indexOf("-- 357.");
        String begin =
                "INSTALL".equals(phase)
                        ? " IF EXISTS(SELECT 1 FROM pg_trigger WHERE tgrelid=target AND NOT"
                                + " tgisinternal"
                        : " IF (SELECT count(*) FROM pg_trigger WHERE tgrelid=target AND NOT"
                                + " tgisinternal)<>2";
        String ending =
                "INSTALL".equals(phase)
                        ? " THEN\n  RAISE EXCEPTION '357事件同名"
                        : " THEN RETURN 0; END IF;";
        int start = script.indexOf(begin, section);
        int end = script.indexOf(ending, start);
        assertTrue(start > section && end > start);
        String predicate =
                script.substring(start + " IF ".length(), end)
                        .replace("tgrelid=target", "tgrelid=42")
                        .replace(
                                "'mom_mes.guard_inspection_event_inbox_v1()'::regprocedure",
                                "'guard'")
                        .replace(
                                "to_regprocedure('mom_mes.guard_inspection_event_inbox_v1()')",
                                "'guard'")
                        .replace("::int2vector", "");
        return db.queryForObject(
                        "SELECT CASE WHEN (" + predicate + ") THEN 1 ELSE 0 END", Integer.class)
                == 1;
    }

    private Object[] row(long id, long tenant, long history, long sequence) throws Exception {
        return new Object[] {
            id,
            tenant,
            "F1",
            "PROCEDURE",
            700L,
            history,
            "RESULT",
            sequence,
            null,
            null,
            java.time.OffsetDateTime.parse("2026-10-26T00:00:00Z"),
            "{}",
            hash("{}"),
            10L,
            java.time.OffsetDateTime.parse("2026-10-26T00:01:00Z")
        };
    }

    private void insert(Object[] row) {
        db.update("INSERT INTO mes_inbox_probe VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", row);
    }

    private String hash(String raw) throws Exception {
        return HexFormat.of()
                .formatHex(
                        MessageDigest.getInstance("SHA-256")
                                .digest(raw.getBytes(StandardCharsets.UTF_8)));
    }
}
