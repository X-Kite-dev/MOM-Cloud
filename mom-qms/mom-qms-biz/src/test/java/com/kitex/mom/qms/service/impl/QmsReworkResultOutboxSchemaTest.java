package com.kitex.mom.qms.service.impl;

import static org.junit.jupiter.api.Assertions.*;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

/** 执行354真实表CHECK及守卫条件；H2不执行PG触发器/目录安装。 */
class QmsReworkResultOutboxSchemaTest {
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
        int start = script.indexOf("CREATE TEMP TABLE mom_qms_rework_result_expected_354 (");
        int end = script.indexOf(") ON COMMIT DROP;", start);
        assertTrue(start > 0 && end > start);
        var ds = new JdbcDataSource();
        ds.setURL(
                "jdbc:h2:mem:rework_schema_"
                        + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        db = new JdbcTemplate(ds);
        db.execute(
                script.substring(start, end + 1)
                        .replace(
                                "CREATE TEMP TABLE mom_qms_rework_result_expected_354",
                                "CREATE TABLE probe")
                        .replace(
                                "encode(sha256(convert_to(payload,'UTF8')),'hex')",
                                "LOWER(RAWTOHEX(HASH('SHA-256',STRINGTOUTF8(payload))))"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "ID",
                "TENANT",
                "FACTORY",
                "ORDER",
                "ZERO_SEQUENCE",
                "SEQUENCE_LIMIT",
                "NEGATIVE_ATTEMPT",
                "UNKNOWN_STATUS",
                "PENDING_NO_NEXT",
                "PENDING_ATTEMPT",
                "DELIVERED_NO_TIME",
                "DELIVERED_HAS_NEXT",
                "DELIVERED_NO_ATTEMPT",
                "DELIVERED_ERROR",
                "ATTENTION_NO_ERROR",
                "ATTENTION_HAS_NEXT",
                "HASH",
                "RAW",
                "UTF8_LIMIT"
            })
    void theActualCheckRejectsAnInvalidIdentityOrFalseReceipt(String invalid) throws Exception {
        Object[] r = row();
        switch (invalid) {
            case "ID" -> r[0] = 0L;
            case "TENANT" -> r[1] = 0L;
            case "FACTORY" -> r[2] = " ";
            case "ORDER" -> r[3] = 0L;
            case "ZERO_SEQUENCE" -> r[4] = 0L;
            case "SEQUENCE_LIMIT" -> r[4] = 5001L;
            case "NEGATIVE_ATTEMPT" -> r[8] = -1;
            case "UNKNOWN_STATUS" -> r[7] = "UNKNOWN";
            case "PENDING_NO_NEXT" -> r[9] = null;
            case "PENDING_ATTEMPT" -> r[8] = 1;
            case "DELIVERED_NO_TIME" -> {
                r[7] = "DELIVERED";
                r[8] = 1;
                r[9] = null;
            }
            case "DELIVERED_HAS_NEXT" -> {
                r[7] = "DELIVERED";
                r[8] = 1;
                r[11] = LocalDateTime.now();
            }
            case "DELIVERED_NO_ATTEMPT" -> {
                r[7] = "DELIVERED";
                r[9] = null;
                r[11] = LocalDateTime.now();
            }
            case "DELIVERED_ERROR" -> {
                r[7] = "DELIVERED";
                r[8] = 1;
                r[9] = null;
                r[11] = LocalDateTime.now();
                r[12] = "failed";
            }
            case "ATTENTION_NO_ERROR" -> {
                r[7] = "ATTENTION";
                r[8] = 8;
                r[9] = null;
            }
            case "ATTENTION_HAS_NEXT" -> {
                r[7] = "ATTENTION";
                r[8] = 8;
                r[12] = "failed";
            }
            case "HASH" -> r[6] = "G".repeat(64);
            case "RAW" -> r[5] = "changed";
            case "UTF8_LIMIT" -> {
                r[5] = "汉".repeat(1400);
                r[6] = hash((String) r[5]);
            }
            default -> throw new AssertionError(invalid);
        }
        assertThrows(
                org.springframework.dao.DataIntegrityViolationException.class, () -> insert(r));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM probe", Integer.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "RETRY_WAIT", "ATTENTION", "DELIVERED"})
    void validProgressPassesTheActualCheck(String status) throws Exception {
        Object[] r = row();
        r[7] = status;
        if (status.equals("RETRY_WAIT")) {
            r[8] = 2;
            r[12] = "retry";
        }
        if (status.equals("ATTENTION")) {
            r[8] = 8;
            r[9] = null;
            r[12] = "needs review";
        }
        if (status.equals("DELIVERED")) {
            r[8] = 1;
            r[9] = null;
            r[11] = LocalDateTime.now();
        }
        insert(r);
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM probe", Integer.class));
    }

    @Test
    void anOrderSequenceIsUniqueOnlyInsideItsActualOrganization() throws Exception {
        insert(row());
        Object[] duplicate = row();
        duplicate[0] = 2L;
        assertThrows(org.springframework.dao.DuplicateKeyException.class, () -> insert(duplicate));
        duplicate[1] = 2L;
        insert(duplicate);
        assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM probe", Integer.class));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "id",
                "tenant_id",
                "factory_code",
                "order_id",
                "sequence_no",
                "payload",
                "fingerprint",
                "created_at",
                "CONFIRMED"
            })
    void theActualGuardRefusesToReplaceTheOriginalOrAConfirmedReceipt(String column)
            throws Exception {
        insert(row());
        db.execute("CREATE TABLE candidate AS SELECT * FROM probe");
        assertFalse(immutableGuardRejects(), "正常进度不应改写原文");
        if (column.equals("CONFIRMED"))
            db.update(
                    "UPDATE probe SET"
                        + " status='DELIVERED',attempt_count=1,next_attempt_time=NULL,delivered_at=CURRENT_TIMESTAMP");
        else
            db.update(
                    "UPDATE candidate SET "
                            + column
                            + "="
                            + switch (column) {
                                case "factory_code", "payload", "fingerprint" -> "'changed'";
                                case "created_at" -> "TIMESTAMP '2026-10-27 00:00:00'";
                                default -> column + "+1";
                            });
        assertTrue(immutableGuardRejects());
    }

    @Test
    void likeIncludingAllMustNotDuplicateTheTemplatesDueIndex() throws Exception {
        // H2不执行PG LIKE。明确模拟PG文档规定的INCLUDING ALL复制语义，再实际执行额外DDL，
        // 用真实JDBC索引集合检测重复；不能把此测试冒称目标PG安装证明。
        int start = script.indexOf("CREATE TEMP TABLE mom_qms_rework_result_expected_354 (");
        int end = script.indexOf(") ON COMMIT DROP;", start);
        db.execute(
                script.substring(start, end + 1)
                        .replace(
                                "CREATE TEMP TABLE mom_qms_rework_result_expected_354",
                                "CREATE TABLE actual")
                        .replace(
                                "encode(sha256(convert_to(payload,'UTF8')),'hex')",
                                "LOWER(RAWTOHEX(HASH('SHA-256',STRINGTOUTF8(payload))))"));
        db.execute("CREATE INDEX probe_due ON probe(next_attempt_time,id)");
        db.execute("CREATE INDEX copied_due ON actual(next_attempt_time,id)");
        int section = script.indexOf("-- 354.");
        int install = script.indexOf("DO $install_354$", section);
        String extra = script.substring(section, install);
        var pattern =
                java.util.regex.Pattern.compile(
                        "CREATE INDEX IF NOT EXISTS (\\w+) ON"
                                + " mom_qms\\.qms_rework_result_outbox\\(([^)]+)\\) WHERE [^;]+;");
        var matches = pattern.matcher(extra);
        while (matches.find())
            db.execute(
                    "CREATE INDEX IF NOT EXISTS "
                            + matches.group(1)
                            + " ON actual("
                            + matches.group(2)
                            + ")");
        assertEquals(
                indexShapes("PROBE"), indexShapes("ACTUAL"), "LIKE已经复制模板索引；额外同形索引会让实际结构多出一项并拒绝安装");
    }

    private List<String> indexShapes(String table) throws Exception {
        var shapes = new java.util.TreeMap<String, StringBuilder>();
        try (var c = db.getDataSource().getConnection();
                var rows = c.getMetaData().getIndexInfo(null, "PUBLIC", table, false, false)) {
            while (rows.next()) {
                String name = rows.getString("INDEX_NAME");
                if (name == null) continue;
                shapes.computeIfAbsent(name, x -> new StringBuilder())
                        .append(rows.getBoolean("NON_UNIQUE"))
                        .append(':')
                        .append(rows.getString("COLUMN_NAME"))
                        .append(';');
            }
        }
        return shapes.values().stream().map(Object::toString).sorted().toList();
    }

    private boolean immutableGuardRejects() {
        int section = script.indexOf("-- 354.");
        int start = script.indexOf("  IF ROW(", section);
        int end = script.indexOf(" THEN RAISE EXCEPTION", start);
        assertTrue(start > section && end > start);
        String predicate =
                script.substring(start + "  IF ".length(), end)
                        .replace("NEW.", "n.")
                        .replace("OLD.", "o.");
        return db.queryForObject(
                "SELECT CASE WHEN ("
                        + predicate
                        + ") THEN TRUE ELSE FALSE END FROM probe o CROSS JOIN candidate n",
                Boolean.class);
    }

    private Object[] row() throws Exception {
        return new Object[] {
            1L,
            1L,
            "F1",
            10L,
            1L,
            "{}",
            hash("{}"),
            "PENDING",
            0,
            LocalDateTime.parse("2026-10-26T01:00:00"),
            LocalDateTime.parse("2026-10-26T01:00:00"),
            null,
            null
        };
    }

    private void insert(Object[] r) {
        db.update(
                "INSERT INTO"
                    + " probe(id,tenant_id,factory_code,order_id,sequence_no,payload,fingerprint,status,attempt_count,next_attempt_time,created_at,delivered_at,last_error)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                r);
    }

    private static String hash(String raw) throws Exception {
        return HexFormat.of()
                .formatHex(
                        MessageDigest.getInstance("SHA-256")
                                .digest(raw.getBytes(StandardCharsets.UTF_8)));
    }
}
