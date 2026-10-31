package com.kitex.mom.mes.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.qms.dto.QmsReworkCompletionDTO;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Actual SQL and Spring transactions; QMS remains an explicitly adapted external boundary. */
public class MesReworkCompletionOutboxTransactionTest {
    MesReworkCompletionTransactionTest fixture;
    JdbcTemplate db;
    final ObjectMapper json = new ObjectMapper();

    public static int installedVersion() {
        return 1;
    }

    @BeforeEach
    void setup() throws Exception {
        fixture = new MesReworkCompletionTransactionTest();
        fixture.setup();
        db = fixture.db;
    }

    @AfterEach
    void close() {
        if (fixture != null) fixture.close();
    }

    private Long notifyCompletion() {
        return fixture.service.notifyReworkCompletion(1L, "RP1", new BigDecimal("2.0"));
    }

    private int count() {
        return db.queryForObject(
                "SELECT COUNT(*) FROM mom_mes.mes_rework_completion_outbox", Integer.class);
    }

    private String raw() {
        assertEquals(1, count(), "first completion command must be durably stored");
        return db.queryForObject(
                "SELECT CAST(payload AS VARCHAR) FROM mom_mes.mes_rework_completion_outbox",
                String.class);
    }

    private void failTransport() {
        when(fixture.remote.mesComplete(any()))
                .thenThrow(new IllegalStateException("response lost"));
    }

    @Test
    void commandIsCommittedOnAnIndependentConnectionBeforeTransport() throws Exception {
        when(fixture.remote.mesComplete(any()))
                .thenAnswer(
                        c -> {
                            try (var cn = db.getDataSource().getConnection();
                                    var st = cn.createStatement();
                                    var rs =
                                            st.executeQuery(
                                                    "SELECT COUNT(*) FROM"
                                                        + " mom_mes.mes_rework_completion_outbox")) {
                                assertTrue(rs.next());
                                assertEquals(
                                        1,
                                        rs.getInt(1),
                                        "remote call must not precede original commit");
                            }
                            return R.ok(901L);
                        });
        assertEquals(901L, notifyCompletion());
    }

    @Test
    void callerRollbackNeverSendsAndDoesNotRetainAnUncommittedOriginal() {
        new TransactionTemplate(fixture.transactions)
                .executeWithoutResult(
                        tx -> {
                            assertNull(notifyCompletion());
                            assertEquals(0, fixture.sends.get());
                            assertEquals(1, count());
                            tx.setRollbackOnly();
                        });
        assertEquals(0, fixture.sends.get());
        assertEquals(0, count());
    }

    @Test
    void callerCommitSendsOnlyAfterTheOriginalAndCompletionAreCommitted() {
        new TransactionTemplate(fixture.transactions)
                .executeWithoutResult(
                        tx -> {
                            assertNull(notifyCompletion());
                            assertEquals(0, fixture.sends.get());
                            assertEquals(1, count());
                        });
        assertEquals(1, fixture.sends.get());
        assertEquals(
                "SUCCEEDED",
                db.queryForObject(
                        "SELECT rework_callback_status FROM mes_task WHERE id=1", String.class));
    }

    @Test
    void receiptSaveFailureKeepsTheAlreadyCommittedCommandForRecovery() {
        fixture.fault.lose = true;
        assertThrows(RuntimeException.class, this::notifyCompletion);
        String original = raw();
        assertEquals(1, fixture.sends.get());
        fixture.fault.lose = false;
        db.update("UPDATE mes_produce_task SET route_name='后改名称' WHERE id=3");
        assertEquals(901L, fixture.service.retryReworkCallback(1L));
        assertEquals(original, raw());
    }

    @Test
    void postCommitSaveFailureDoesNotReportCommittedCallerAsRolledBack() {
        fixture.fault.lose = true;
        assertDoesNotThrow(
                () ->
                        new TransactionTemplate(fixture.transactions)
                                .executeWithoutResult(
                                        tx -> {
                                            assertNull(notifyCompletion());
                                        }));
        assertEquals(1, count());
        assertNull(
                db.queryForObject(
                        "SELECT rework_callback_status FROM mes_task WHERE id=1", String.class));
        fixture.fault.lose = false;
        assertEquals(901L, fixture.service.retryReworkCallback(1L));
    }

    @Test
    void lostResponseReplaysOriginalNameAndCanonicalQuantityAfterLocalRename() {
        var calls = new AtomicInteger();
        when(fixture.remote.mesComplete(any()))
                .thenAnswer(
                        c -> {
                            QmsReworkCompletionDTO dto = c.getArgument(0);
                            if (calls.incrementAndGet() == 1)
                                throw new IllegalStateException("QMS accepted, response lost");
                            assertEquals("接线", dto.getProcessName());
                            assertEquals(new BigDecimal("2"), dto.getCompletedQuantity());
                            return R.ok(901L);
                        });
        assertNull(notifyCompletion());
        String original = raw();
        db.update("UPDATE mes_produce_task SET route_name='接线新版' WHERE id=3");
        var target =
                org.springframework.aop.framework.AopProxyUtils.getSingletonTarget(fixture.service);
        org.springframework.test.util.ReflectionTestUtils.setField(
                target, "reworkCompletions", fixture.newCoordinator());
        assertEquals(901L, fixture.service.retryReworkCallback(1L));
        assertEquals(original, raw());
        assertEquals(2, calls.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"FAILED", "SUCCEEDED"})
    void attemptedLegacyWithoutAnOriginalCannotReconstructIt(String status) {
        db.update(
                "UPDATE mes_task SET"
                        + " rework_callback_status=?,rework_callback_count=1,reinspection_task_id=?"
                        + " WHERE id=1",
                status,
                "SUCCEEDED".equals(status) ? 901L : null);
        assertThrows(RuntimeException.class, () -> fixture.service.retryReworkCallback(1L));
        assertEquals(0, fixture.sends.get());
        assertEquals(0, count());
    }

    @Test
    void retryDoesNotGuessAReplacementFinalTask() {
        failTransport();
        assertNull(notifyCompletion());
        raw();
        db.update("UPDATE mes_produce_task SET code='RP-REPLACED' WHERE id=3");
        assertThrows(RuntimeException.class, () -> fixture.service.retryReworkCallback(1L));
        verify(fixture.remote, times(1)).mesComplete(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"hash", "payload", "time", "task", "tenant", "oversize"})
    void corruptOriginalStopsBeforeAnotherRemoteCall(String corruption) throws Exception {
        failTransport();
        assertNull(notifyCompletion());
        String original = raw();
        switch (corruption) {
            case "hash" ->
                    db.update(
                            "UPDATE mom_mes.mes_rework_completion_outbox SET payload_fingerprint=?",
                            "f".repeat(64));
            case "payload" -> {
                ObjectNode value = (ObjectNode) json.readTree(original);
                value.put("taskId", 999L);
                String changed = value.toString();
                db.update(
                        "UPDATE mom_mes.mes_rework_completion_outbox SET"
                                + " payload=?,payload_fingerprint=?",
                        changed,
                        hash(changed));
            }
            case "time" ->
                    db.update(
                            "UPDATE mom_mes.mes_rework_completion_outbox SET created_at=TIMESTAMP"
                                    + " '2000-01-01 00:00:00'");
            case "task" -> db.update("UPDATE mom_mes.mes_rework_completion_outbox SET task_id=999");
            case "tenant" ->
                    db.update("UPDATE mom_mes.mes_rework_completion_outbox SET tenant_id=2");
            case "oversize" ->
                    db.update(
                            "UPDATE mom_mes.mes_rework_completion_outbox SET payload=?",
                            "x".repeat(65537));
        }
        assertThrows(RuntimeException.class, () -> fixture.service.retryReworkCallback(1L));
        verify(fixture.remote, times(1)).mesComplete(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"process", "quantity", "source", "originalDeleted", "operationDeleted"})
    void changedActualExecutionCannotSendAStoredOriginal(String changed) {
        failTransport();
        assertNull(notifyCompletion());
        raw();
        switch (changed) {
            case "process" ->
                    db.update("UPDATE mes_produce_task SET route_code='OTHER' WHERE id=3");
            case "quantity" -> db.update("UPDATE mes_produce_task SET complete_num=1 WHERE id=3");
            case "source" -> db.update("UPDATE mes_task SET source_handle_record_id=99 WHERE id=1");
            case "originalDeleted" -> db.update("UPDATE mes_task SET del_flag='1' WHERE id=2");
            case "operationDeleted" ->
                    db.update("UPDATE mes_produce_task SET del_flag='1' WHERE id=3");
        }
        assertThrows(RuntimeException.class, () -> fixture.service.retryReworkCallback(1L));
        verify(fixture.remote, times(1)).mesComplete(any());
    }

    @Test
    void necessaryOriginalInsertZeroRollsBackTheCallingCompletionBeforeAnySend() {
        fixture.fault.loseOriginal = true;
        db.update("UPDATE mes_task SET status='PRODUCT' WHERE id=1");
        assertThrows(
                RuntimeException.class,
                () ->
                        new TransactionTemplate(fixture.transactions)
                                .executeWithoutResult(
                                        tx -> {
                                            db.update(
                                                    "UPDATE mes_task SET status='FINISH' WHERE"
                                                            + " id=1");
                                            notifyCompletion();
                                        }));
        assertEquals(
                "PRODUCT",
                db.queryForObject("SELECT status FROM mes_task WHERE id=1", String.class));
        assertEquals(0, count());
        assertEquals(0, fixture.sends.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"failureWithData", "nullResponse", "negativeReceipt"})
    void aFailedOrInvalidRemoteReceiptCannotConfirmAnOriginal(String response) {
        var failed = R.<Long>failed("rejected");
        failed.setData(901L);
        when(fixture.remote.mesComplete(any()))
                .thenReturn(
                        switch (response) {
                            case "failureWithData" -> failed;
                            case "nullResponse" -> null;
                            default -> R.ok(-1L);
                        });
        assertNull(notifyCompletion());
        assertEquals(1, count());
        assertEquals(
                "FAILED",
                db.queryForObject(
                        "SELECT rework_callback_status FROM mes_task WHERE id=1", String.class));
        assertNull(
                db.queryForObject(
                        "SELECT reinspection_task_id FROM mes_task WHERE id=1", Long.class));
    }

    @Test
    void missingInstallationStopsBeforeSending() {
        db.execute("DROP ALIAS mom_mes.mom_rework_completion_outbox_ready_v1");
        assertThrows(RuntimeException.class, this::notifyCompletion);
        assertEquals(0, fixture.sends.get());
        assertEquals(0, count());
    }

    @Test
    void concurrentRetriesSerializeOnTheActualWorkorder() throws Exception {
        failTransport();
        assertNull(notifyCompletion());
        raw();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        doAnswer(
                        c -> {
                            calls.incrementAndGet();
                            entered.countDown();
                            assertTrue(release.await(5, TimeUnit.SECONDS));
                            return R.ok(901L);
                        })
                .when(fixture.remote)
                .mesComplete(any());
        var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(this::retryInScope);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var b = pool.submit(this::retryInScope);
            assertThrows(TimeoutException.class, () -> b.get(100, TimeUnit.MILLISECONDS));
            release.countDown();
            assertEquals(901L, a.get(5, TimeUnit.SECONDS));
            assertEquals(901L, b.get(5, TimeUnit.SECONDS));
            assertEquals(1, calls.get());
            assertEquals(1, count());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private Long retryInScope() {
        TenantContextHolder.setTenantId(1L);
        FactoryContextHolder.setFactoryCode("F1");
        try {
            return fixture.service.retryReworkCallback(1L);
        } finally {
            TenantContextHolder.clear();
            FactoryContextHolder.clear();
        }
    }

    private static String hash(String s) throws Exception {
        return HexFormat.of()
                .formatHex(
                        MessageDigest.getInstance("SHA-256")
                                .digest(s.getBytes(StandardCharsets.UTF_8)));
    }
}
