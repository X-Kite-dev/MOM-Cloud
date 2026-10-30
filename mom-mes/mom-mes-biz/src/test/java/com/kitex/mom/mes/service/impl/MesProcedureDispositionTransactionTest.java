package com.kitex.mom.mes.service.impl;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kitex.mom.qms.dto.inspection.QmsProcedureDispositionContract;
import com.kitex.mom.qms.dto.resp.HandleFinishDTO;

import org.junit.jupiter.api.*;

import java.util.*;

/** 真实普通收件/应用/报工/库存事务；QMS冻结原件作为签名投递输入。 */
class MesProcedureDispositionTransactionTest {
    MesInspectionEventApplicationTransactionTest f;
    ObjectNode previous;

    @BeforeEach
    void setup() throws Exception {
        f = new MesInspectionEventApplicationTransactionTest();
        f.setup();
        f.db.execute(
                "CREATE ALIAS mom_mes.mom_procedure_disposition_ready_v1 AS 'Integer ready(){return"
                        + " 1;}'");
        f.receive(f.first());
        assertTrue(f.worker.consume(10L));
        previous = f.result(f.first(), "UN_QUALIFIED", 0, 0);
        f.receive(previous);
        assertTrue(f.worker.consume(10L));
    }

    @AfterEach
    void clear() {
        if (f != null) f.clear();
    }

    ObjectNode disposition(ObjectNode base, String kind, int quantity) throws Exception {
        var n = f.next(base);
        var command =
                new HandleFinishDTO()
                        .setId(700L)
                        .setRequestId(UUID.randomUUID().toString())
                        .setHandleNum(quantity)
                        .setHandleResult(kind)
                        .setHandleRemark("按实际批准量继续生产")
                        .setAuthorizationRef("AUTH-01")
                        .setConcessionUnrestricted(true);
        Date now = new Date();
        String raw =
                QmsProcedureDispositionContract.create(
                        new QmsProcedureDispositionContract.Identity(
                                Long.valueOf(n.path("eventId").textValue()) + 100L,
                                1L,
                                "F1",
                                700L,
                                61L,
                                Long.valueOf(n.path("historyId").textValue()),
                                4L,
                                "质量员",
                                now),
                        command,
                        Long.valueOf(base.path("eventId").textValue()),
                        MesInspectionEventApplicationTransactionTest.hash(
                                MesInspectionEventApplicationTransactionTest.JSON
                                        .writeValueAsString(base)),
                        base.get("source"));
        var p = QmsProcedureDispositionContract.parse(raw);
        n.put("schemaVersion", 2)
                .put("eventType", "DISPOSITION")
                .put("quantity", quantity)
                .putNull("qualified");
        n.put("occurredAt", now.toInstant().toString())
                .put("recordedBy", "4")
                .put("dispositionPayload", raw)
                .put("dispositionFingerprint", QmsProcedureDispositionContract.hash(raw));
        n.set("source", p.after());
        return n;
    }

    @Test
    void realPartialReleaseThenConcessionAdvancesStockAndWorkGateExactlyOnce() throws Exception {
        var part = disposition(previous, "RELEASE", 2);
        f.receive(part);
        assertTrue(f.worker.consume(10L));
        f.quantity("quality_released_num", 2);
        f.quantity("quality_pending_num", 3);
        assertEquals(
                "UN_QUALIFIED",
                f.projection
                        .resolve(f.states.generation(10L, 1L, "F1"), true, null, null, new Date())
                        .task()
                        .getResult());
        var last = disposition(part, "CONCESSION", 3);
        f.receive(last);
        assertTrue(f.worker.consume(10L));
        f.quantity("quality_released_num", 5);
        f.quantity("quality_pending_num", 0);
        assertEquals(
                "RECEIVE",
                f.projection
                        .resolve(f.states.generation(10L, 1L, "F1"), true, null, null, new Date())
                        .task()
                        .getResult());
        assertFalse(f.worker.consume(10L));
        f.receive(last);
        assertFalse(f.worker.consume(10L));
        assertEquals(
                4,
                f.db.queryForObject(
                        "SELECT COUNT(*) FROM mom_mes.mes_inspection_event_application",
                        Integer.class));
        f.quantity("quality_released_num", 5);
    }

    @Test
    void forgedSourceOrPermissionFlagNeverReleasesAdditionalStock() throws Exception {
        var n = disposition(previous, "RELEASE", 2);
        ((ObjectNode) n.get("source")).put("usableNum", 5);
        assertThrows(RuntimeException.class, () -> f.receive(n));
        f.quantity("quality_released_num", 0);
        assertEquals(2L, f.sequence());
    }

    @Test
    void noncontiguousDispositionKeepsTheOriginalCursorAndNoQuantitySideEffects() throws Exception {
        var part = disposition(previous, "RELEASE", 2);
        var last = disposition(part, "RELEASE", 3);
        assertThrows(RuntimeException.class, () -> f.receive(last));
        assertEquals(2L, f.sequence());
        f.quantity("quality_released_num", 0);
    }

    @Test
    void failedApplicationRollsBackInventoryReceiptsAndCanResumeOriginal() throws Exception {
        var part = disposition(previous, "RELEASE", 2);
        f.receive(part);
        f.fault.kind = "RECEIPT";
        assertThrows(RuntimeException.class, () -> f.worker.consume(10L));
        assertEquals(2L, f.sequence());
        f.quantity("quality_released_num", 0);
        f.fault.kind = "";
        assertTrue(f.worker.consume(10L));
        f.quantity("quality_released_num", 2);
    }
}
