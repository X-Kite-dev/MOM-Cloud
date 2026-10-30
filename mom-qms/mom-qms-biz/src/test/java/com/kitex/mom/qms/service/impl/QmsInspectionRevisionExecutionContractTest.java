package com.kitex.mom.qms.service.impl;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kitex.mom.qms.dto.inspection.QmsInspectionRevisionExecutionContract;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;

/** 使用真实Task76申请/独立审核；冻结原文不代表QMS已提交执行。 */
public class QmsInspectionRevisionExecutionContractTest {
    QmsInspectionRevisionTransactionTest origin;
    com.kitex.mom.qms.entity.QmsInspectionRevisionEntity request;
    com.kitex.mom.qms.entity.QmsInspectionRevisionReviewEntity review;

    @BeforeEach
    void setup() throws Exception {
        origin = new QmsInspectionRevisionTransactionTest();
        origin.setup();
        request = origin.request();
        origin.actor(3);
        review = origin.service.review(700L, request.getId(), origin.review("APPROVED"));
    }

    @AfterEach
    void clear() {
        origin.clear();
    }

    @Test
    void realImmutableApplicationAndIndependentApprovalProduceExactFrozenIntent() {
        String raw = QmsInspectionRevisionExecutionContract.freeze(request, review, 4L, new Date());
        var parsed = QmsInspectionRevisionExecutionContract.parse(raw);
        assertEquals(request.getId().toString(), parsed.request().path("requestId").textValue());
        assertEquals("3", parsed.review().path("reviewedBy").textValue());
        assertTrue(parsed.request().path("source").get("matBarcode").isNull());
        assertEquals(request.getPayload(), parsed.requestPayload());
        assertEquals(4L, parsed.executorId());
    }

    @Test
    void noSelfReviewOrNonApprovalCanBeFrozen() {
        review.setReviewedBy(request.getRequestedBy());
        assertThrows(
                RuntimeException.class,
                () ->
                        QmsInspectionRevisionExecutionContract.freeze(
                                request, review, 4L, new Date()));
        review.setReviewedBy(3L).setDecision("REJECTED");
        assertThrows(
                RuntimeException.class,
                () ->
                        QmsInspectionRevisionExecutionContract.freeze(
                                request, review, 4L, new Date()));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "REQUEST_RAW",
                "INDEX",
                "REQUEST_FP",
                "REVIEW_SCOPE",
                "REVIEW_FP",
                "EXECUTOR",
                "TIME"
            })
    void badOwnerBasisCannotBeFrozen(String bad) {
        switch (bad) {
            case "REQUEST_RAW" -> request.setPayload(request.getPayload() + " ");
            case "INDEX" -> request.setTaskId(701L);
            case "REQUEST_FP" -> request.setFingerprint("b".repeat(64));
            case "REVIEW_SCOPE" -> review.setFactoryCode("OTHER");
            case "REVIEW_FP" -> review.setRequestFingerprint("b".repeat(64));
            case "EXECUTOR" -> {}
            case "TIME" -> {}
        }
        assertThrows(
                RuntimeException.class,
                () ->
                        QmsInspectionRevisionExecutionContract.freeze(
                                request,
                                review,
                                "EXECUTOR".equals(bad) ? null : 4L,
                                "TIME".equals(bad) ? new Date(0) : new Date()));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "DUPLICATE",
                "TRAILING",
                "UNKNOWN",
                "HASH",
                "NUMERIC_ID",
                "MISSING_NULL",
                "OVERSIZE"
            })
    void decodingIsStrictAndDoesNotInventNullableFields(String bad) throws Exception {
        String raw = QmsInspectionRevisionExecutionContract.freeze(request, review, 4L, new Date());
        var json = new ObjectMapper();
        var root = (ObjectNode) json.readTree(raw);
        switch (bad) {
            case "DUPLICATE" -> raw = raw.substring(0, raw.length() - 1) + ",\"executorId\":\"5\"}";
            case "TRAILING" -> raw += " {}";
            case "UNKNOWN" -> {
                root.put("allowRevision", true);
                raw = json.writeValueAsString(root);
            }
            case "HASH" -> {
                root.put("requestFingerprint", "b".repeat(64));
                raw = json.writeValueAsString(root);
            }
            case "NUMERIC_ID", "MISSING_NULL" -> {
                var req = (ObjectNode) json.readTree(root.path("requestPayload").textValue());
                if ("NUMERIC_ID".equals(bad)) req.put("requestId", request.getId());
                else ((ObjectNode) req.get("source")).remove("matBarcode");
                String changed = json.writeValueAsString(req);
                root.put("requestPayload", changed);
                root.put(
                        "requestFingerprint",
                        QmsInspectionRevisionExecutionContract.fingerprint(changed));
                raw = json.writeValueAsString(root);
            }
            case "OVERSIZE" -> raw = " ".repeat(131073);
            default -> fail(bad);
        }
        String altered = raw;
        assertThrows(
                RuntimeException.class,
                () -> QmsInspectionRevisionExecutionContract.parse(altered));
    }
}
