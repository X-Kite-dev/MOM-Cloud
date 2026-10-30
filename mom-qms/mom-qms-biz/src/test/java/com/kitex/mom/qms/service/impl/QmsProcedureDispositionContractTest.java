package com.kitex.mom.qms.service.impl;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kitex.mom.qms.dto.inspection.QmsProcedureDispositionContract;
import com.kitex.mom.qms.dto.resp.HandleFinishDTO;

import org.junit.jupiter.api.Test;

import java.util.*;

class QmsProcedureDispositionContractTest {
    static final ObjectMapper JSON = new ObjectMapper();

    static ObjectNode source() {
        var n = JSON.createObjectNode();
        for (String k :
                List.of(
                        "sourceTaskCode",
                        "sourceRequestId",
                        "sourceRequestFingerprint",
                        "matCode",
                        "processCode",
                        "labelType",
                        "standardId",
                        "batchNo",
                        "num",
                        "inspectionNum",
                        "status",
                        "result",
                        "usableNum",
                        "handleNum",
                        "handleResult",
                        "orderNo",
                        "orderId",
                        "matBarcode",
                        "deptCode",
                        "latestInspectionTime",
                        "latestAppendTime")) n.putNull(k);
        n.put("sourceTaskCode", "P1")
                .put("sourceRequestId", "MES_QMS_V2:production-first")
                .put("sourceRequestFingerprint", "a".repeat(64))
                .put("matCode", "MAT")
                .put("processCode", "OP")
                .put("labelType", "FIRST")
                .put("standardId", "8")
                .put("num", 5)
                .put("inspectionNum", 1)
                .put("status", "UNHANDLED")
                .put("result", "UN_QUALIFIED")
                .put("usableNum", 0)
                .put("handleNum", 0)
                .put("orderNo", "T1")
                .put("orderId", "9007199254740993")
                .put("latestInspectionTime", 1L);
        return n;
    }

    static HandleFinishDTO command(String kind, int quantity) {
        return new HandleFinishDTO()
                .setId(700L)
                .setRequestId(UUID.randomUUID().toString())
                .setHandleResult(kind)
                .setHandleNum(quantity)
                .setHandleRemark("确认按处置量继续生产")
                .setAuthorizationRef("AUTH-2026-10")
                .setConcessionUnrestricted(true);
    }

    static String packet(ObjectNode before, HandleFinishDTO cmd) {
        return QmsProcedureDispositionContract.create(
                new QmsProcedureDispositionContract.Identity(
                        31L, 1L, "F1", 700L, 41L, 51L, 9007199254740993L, "质检员", new Date(1000)),
                cmd,
                12L,
                "b".repeat(64),
                before);
    }

    static ObjectNode event(String raw) {
        var p = QmsProcedureDispositionContract.parse(raw);
        var b = p.body();
        var n = JSON.createObjectNode();
        n.put("schemaVersion", 2)
                .put("sourceSystem", "QMS")
                .put("eventId", "61")
                .put("tenantId", "1")
                .put("factoryCode", "F1")
                .put("inspectionType", "PROCEDURE")
                .put("taskId", "700")
                .put("historyId", "51")
                .put("eventType", "DISPOSITION")
                .put("sequenceNo", 3)
                .put("previousEventId", "12")
                .put("previousFingerprint", "b".repeat(64))
                .put("occurredAt", "1970-01-01T00:00:01Z")
                .put("quantity", p.quantity())
                .putNull("qualified");
        n.putNull("cancelReason");
        n.put("recordedBy", "9007199254740993");
        n.set("source", p.after());
        n.put("firstPassEligible", false);
        n.putArray("dataGaps");
        n.put("dispositionPayload", raw)
                .put("dispositionFingerprint", QmsProcedureDispositionContract.hash(raw));
        return n;
    }

    @Test
    void partialReleaseThenConcessionCompletesWithoutChangingOriginalJudgment() {
        var first = QmsProcedureDispositionContract.parse(packet(source(), command("RELEASE", 2)));
        assertEquals("UN_QUALIFIED", first.after().path("result").textValue());
        assertEquals(2, first.after().path("usableNum").intValue());
        var last =
                QmsProcedureDispositionContract.parse(
                        packet((ObjectNode) first.after(), command("CONCESSION", 3)));
        assertEquals("RECEIVE", last.after().path("result").textValue());
        assertEquals("FINISHED", last.after().path("status").textValue());
        assertEquals(5, last.after().path("handleNum").intValue());
        assertEquals("CONCESSION,RELEASE", last.after().path("handleResult").textValue());
        assertEquals("UN_QUALIFIED", source().path("result").textValue());
        assertEquals("9007199254740993", last.body().path("handledBy").textValue());
        QmsProcedureDispositionContract.validateEvent(
                event(packet(source(), command("RELEASE", 2))));
    }

    @Test
    void concessionRequiresTraceableUnrestrictedAuthorization() {
        assertThrows(
                IllegalArgumentException.class,
                () -> packet(source(), command("CONCESSION", 1).setAuthorizationRef(null)));
        assertThrows(
                IllegalArgumentException.class,
                () -> packet(source(), command("CONCESSION", 1).setConcessionUnrestricted(false)));
    }

    @Test
    void eventCannotChangeFrozenProductOrFalselyDeclareFirstPass() {
        var n = event(packet(source(), command("RELEASE", 2)));
        ((ObjectNode) n.get("source")).put("matCode", "OTHER");
        ObjectNode productForged = n;
        assertThrows(
                IllegalArgumentException.class,
                () -> QmsProcedureDispositionContract.validateEvent(productForged));
        n = event(packet(source(), command("RELEASE", 2)));
        n.put("firstPassEligible", true);
        ObjectNode forged = n;
        assertThrows(
                IllegalArgumentException.class,
                () -> QmsProcedureDispositionContract.validateEvent(forged));
    }

    @Test
    void unknownDuplicateAndTrailingFieldsRejectInsteadOfLaundering() {
        String raw = packet(source(), command("RELEASE", 2));
        assertThrows(
                IllegalArgumentException.class,
                () -> QmsProcedureDispositionContract.parse(raw + " {}"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        QmsProcedureDispositionContract.parse(
                                raw.substring(0, raw.length() - 1) + ",\"kind\":\"CONCESSION\"}"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        QmsProcedureDispositionContract.parse(
                                raw.substring(0, raw.length() - 1) + ",\"skipGate\":true}"));
    }

    @Test
    void amountsAndActionsNeverBypassTheOrdinaryDispositionBoundary() {
        assertThrows(IllegalArgumentException.class, () -> packet(source(), command("RELEASE", 6)));
        assertThrows(IllegalArgumentException.class, () -> packet(source(), command("SMASH", 1)));
        ObjectNode old = source().put("result", "QUALIFIED");
        assertThrows(IllegalArgumentException.class, () -> packet(old, command("RELEASE", 1)));
    }
}
