package com.kitex.mom.mes.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kitex.common.core.constant.SecurityConstants;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.MomSecurityInnerAspect;
import com.kitex.mom.mes.controller.MesInspectionEventDeliveryController;
import com.kitex.mom.mes.dto.*;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.mapper.*;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** 复用Task71实际创建账夹具；收件执行真实Mapper/Spring事务，无QMS网络。 */
class MesInspectionEventInboxTransactionTest {
    static final String SECRET = "ordinary-inspection-write-key-for-tests-only";
    static final ObjectMapper JSON = new ObjectMapper();
    MesQualityGateSourceTransactionTest origin;
    JdbcTemplate db;
    MesInspectionEventInboxService inbox;
    DataSourceTransactionManager tx;
    ObjectNode first;
    WriteFault fault = new WriteFault();

    @BeforeEach
    void setup() throws Exception {
        origin = new MesQualityGateSourceTransactionTest();
        origin.setup();
        db = (JdbcTemplate) ReflectionTestUtils.getField(origin, "db");
        tx = (DataSourceTransactionManager) ReflectionTestUtils.getField(origin, "tx");
        var session = (SqlSessionTemplate) ReflectionTestUtils.getField(origin, "session");
        var config = session.getConfiguration();
        ReflectionTestUtils.invokeMethod(
                origin, "table", config, MesInspectionEventInboxEntity.class);
        config.addMapper(MesInspectionEventInboxMapper.class);
        ReflectionTestUtils.invokeMethod(
                origin, "table", config, MesInspectionEventStateEntity.class);
        config.addMapper(MesInspectionEventStateMapper.class);
        db.execute(
                "CREATE ALIAS mom_mes.mom_inspection_event_application_ready_v1 AS 'Integer"
                        + " ready(){return 1;}'");
        config.addInterceptor(fault);
        db.execute(
                "CREATE ALIAS mom_mes.mom_inspection_event_inbox_ready_v1 AS 'Integer"
                        + " ready(){return 1;}'");
        db.execute(
                "ALTER TABLE mom_mes.mes_inspection_event_inbox ADD"
                        + " UNIQUE(tenant_id,factory_code,inspection_type,task_id,sequence_no)");
        db.execute(
                "ALTER TABLE mom_mes.mes_inspection_event_inbox ADD"
                        + " UNIQUE(tenant_id,factory_code,inspection_type,history_id)");
        var target =
                new MesInspectionEventInboxService(
                        session.getMapper(MesInspectionEventInboxMapper.class),
                        new MesQualityGateSourceEvidence(
                                session.getMapper(MesQmsInspectionCommandMapper.class),
                                session.getMapper(MesQualityGateSourceMapper.class)),
                        session.getMapper(MesInspectionEventStateMapper.class));
        ReflectionTestUtils.setField(target, "deliverySecret", SECRET);
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(tx, new AnnotationTransactionAttributeSource()));
        inbox = (MesInspectionEventInboxService) proxy.getProxy();
        first = JSON.createObjectNode();
        first.put("schemaVersion", 1);
        first.put("sourceSystem", "QMS");
        first.put("eventId", "9001");
        first.put("tenantId", "1");
        first.put("factoryCode", "F1");
        first.put("inspectionType", "PROCEDURE");
        first.put("taskId", "700");
        first.put("historyId", "8001");
        first.put("eventType", "CREATE");
        first.put("sequenceNo", 1);
        first.putNull("previousEventId");
        first.putNull("previousFingerprint");
        first.put("occurredAt", "2026-10-26T00:00:00Z");
        first.put("quantity", 5);
        first.putNull("qualified");
        first.putNull("cancelReason");
        first.put("recordedBy", "operator");
        first.put("firstPassEligible", false);
        first.putArray("dataGaps").add("未冻结单位和稳定节点");
        ObjectNode source = first.putObject("source");
        source.put("sourceTaskCode", "P1");
        source.put("sourceRequestId", "MES_QMS_V2:P1:FIRST:10");
        source.put(
                "sourceRequestFingerprint",
                db.queryForObject(
                        "SELECT command_fingerprint FROM mom_mes.mes_qms_inspection_command WHERE"
                                + " id=10",
                        String.class));
        source.put("matCode", "M1");
        source.put("processCode", "OP1");
        source.put("labelType", "FIRST");
        source.put("standardId", "9");
        source.put("batchNo", "B1");
        source.put("num", 5);
        source.put("inspectionNum", 1);
        source.put("status", "WAIT");
        source.putNull("result");
        source.putNull("usableNum");
        source.putNull("handleNum");
        source.putNull("handleResult");
        source.put("orderNo", "T1");
        source.put("orderId", "100");
        source.putNull("matBarcode");
        source.putNull("deptCode");
        source.putNull("latestInspectionTime");
        source.putNull("latestAppendTime");
    }

    @AfterEach
    void clear() {
        origin.clear();
    }

    @Test
    void aSignedFrozenEventIsStoredWithoutReleasingProduction() throws Exception {
        var d = delivery(first);
        var receipt = inbox.accept(d);
        assertNotNull(receipt);
        assertEquals("RECEIVED", receipt.getStatus());
        assertEquals(false, receipt.getApplied());
        assertEquals(9001L, receipt.getEventId());
        assertEquals(700L, receipt.getTaskId());
        assertEquals(1L, receipt.getSequenceNo());
        assertEquals(d.getFingerprint(), receipt.getFingerprint());
        assertNotNull(receipt.getReceivedAt());
        assertEquals(1, count());
        assertEquals(
                d.getPayload(),
                db.queryForObject(
                        "SELECT payload FROM mom_mes.mes_inspection_event_inbox", String.class));
        assertEquals(
                "PENDING",
                db.queryForObject(
                        "SELECT gate_status FROM mom_mes.mes_inspection_gen_record WHERE id=10",
                        String.class));
        assertNull(
                db.queryForObject(
                        "SELECT qms_usable_num FROM mom_mes.mes_inspection_gen_record WHERE id=10",
                        Integer.class));
    }

    @Test
    void duplicateDeliveryReturnsTheOriginalReceiptAndTime() throws Exception {
        var d = delivery(first);
        var a = inbox.accept(d);
        var b = inbox.accept(delivery(first));
        assertNotNull(a);
        assertNotNull(b);
        assertEquals(1, count());
        assertEquals(a, b);
    }

    @Test
    void aCompleteLinkedChainAcceptsTheFiveExistingActions() throws Exception {
        ObjectNode current = first;
        for (String action : List.of("CREATE", "APPEND", "RESULT", "EXEMPTION", "CANCELED")) {
            current.put("eventType", action);
            assertNotNull(inbox.accept(delivery(current)));
            current = next(current);
        }
        assertEquals(5, count());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "EVENT_ID",
                "HISTORY_ID",
                "SEQUENCE_GAP",
                "PREVIOUS_ID",
                "PREVIOUS_SHA",
                "TYPE",
                "FIRST_CERTIFICATION",
                "SOURCE_REQUEST",
                "SOURCE_HASH",
                "MATERIAL",
                "PROCESS",
                "PHASE",
                "ORDER",
                "ROOT_ID",
                "TASK_ID",
                "TENANT",
                "FACTORY",
                "SCHEMA",
                "ACTION",
                "SAMPLE_DECIMAL",
                "MISSING_NULLABLE",
                "QUANTITY_DECIMAL",
                "TIME"
            })
    void aConflictOrInvalidFrozenIdentityCannotBeAcknowledged(String bad) throws Exception {
        assertNotNull(inbox.accept(delivery(first)));
        ObjectNode n = next(first);
        ObjectNode source = (ObjectNode) n.get("source");
        switch (bad) {
            case "EVENT_ID" -> n.put("eventId", "9001");
            case "HISTORY_ID" -> n.put("historyId", "8001");
            case "SEQUENCE_GAP" -> n.put("sequenceNo", 3);
            case "PREVIOUS_ID" -> n.put("previousEventId", "9999");
            case "PREVIOUS_SHA" -> n.put("previousFingerprint", "a".repeat(64));
            case "TYPE" -> n.put("inspectionType", "PRODUCT");
            case "FIRST_CERTIFICATION" -> n.put("firstPassEligible", true);
            case "SOURCE_REQUEST" -> source.put("sourceRequestId", "MES_QMS_V2:other");
            case "SOURCE_HASH" -> source.put("sourceRequestFingerprint", "a".repeat(64));
            case "MATERIAL" -> source.put("matCode", "M2");
            case "PROCESS" -> source.put("processCode", "OP2");
            case "PHASE" -> source.put("labelType", "LAST");
            case "ORDER" -> source.put("orderNo", "T2");
            case "ROOT_ID" -> source.put("orderId", "200");
            case "TASK_ID" -> n.put("taskId", "701");
            case "TENANT" -> n.put("tenantId", "2");
            case "FACTORY" -> n.put("factoryCode", "F2");
            case "SCHEMA" -> n.put("schemaVersion", 2);
            case "ACTION" -> n.put("eventType", "RELEASE");
            case "SAMPLE_DECIMAL" -> source.put("inspectionNum", 1.5);
            case "MISSING_NULLABLE" -> source.remove("usableNum");
            case "QUANTITY_DECIMAL" -> source.put("num", 5.5);
            case "TIME" -> n.put("occurredAt", "2026-10-26 10:00:00");
            default -> fail(bad);
        }
        assertThrows(RuntimeException.class, () -> inbox.accept(delivery(n)));
        assertEquals(1, count());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "SIGNATURE",
                "READ_KEY",
                "REWORK_DOMAIN",
                "EXPIRED",
                "FUTURE",
                "NONCE",
                "TENANT",
                "FACTORY",
                "SKIP",
                "FACTORY_SKIP",
                "SHA",
                "OVERSIZE",
                "DUPLICATE_JSON",
                "NULL"
            })
    void serviceAuthorizationAndRawEnvelopeAreRequired(String bad) throws Exception {
        var d = delivery(first);
        switch (bad) {
            case "SIGNATURE" -> d.setSignature("a".repeat(64));
            case "READ_KEY" -> d.setSignature(d.sign("independent-unrelated-reader-test-secret"));
            case "REWORK_DOMAIN" -> {
                var x =
                        new MesReworkInspectionDeliveryDTO()
                                .setEventId(d.getEventId())
                                .setTenantId(1L)
                                .setFactoryCode("F1")
                                .setPayload(d.getPayload())
                                .setFingerprint(d.getFingerprint())
                                .setIssuedAt(d.getIssuedAt())
                                .setNonce(d.getNonce());
                d.setSignature(x.sign(SECRET));
            }
            case "EXPIRED" -> {
                d.setIssuedAt(Instant.now().getEpochSecond() - 100);
                resign(d);
            }
            case "FUTURE" -> {
                d.setIssuedAt(Instant.now().getEpochSecond() + 25);
                resign(d);
            }
            case "NONCE" -> {
                d.setNonce("short");
                resign(d);
            }
            case "TENANT" -> TenantContextHolder.setTenantId(2L);
            case "FACTORY" -> FactoryContextHolder.setFactoryCode("F2");
            case "SKIP" -> TenantContextHolder.setTenantSkip();
            case "FACTORY_SKIP" -> FactoryContextHolder.setFactorySkip();
            case "SHA" -> {
                d.setFingerprint("a".repeat(64));
                resign(d);
            }
            case "OVERSIZE" -> {
                d.setPayload("浇".repeat(22000));
                d.setFingerprint(hash(d.getPayload()));
                resign(d);
            }
            case "DUPLICATE_JSON" -> {
                d.setPayload(
                        d.getPayload()
                                .replace(
                                        "\"schemaVersion\":1",
                                        "\"schemaVersion\":1,\"schemaVersion\":1"));
                d.setFingerprint(hash(d.getPayload()));
                resign(d);
            }
            case "NULL" -> d = null;
            default -> fail(bad);
        }
        var invalid = d;
        assertThrows(RuntimeException.class, () -> inbox.accept(invalid));
        assertEquals(0, count());
    }

    @Test
    void theFirstReceiptCannotStartInTheMiddleOfAHistoricalChain() throws Exception {
        assertThrows(RuntimeException.class, () -> inbox.accept(delivery(next(first))));
        assertEquals(0, count());
    }

    @Test
    void changedSourceBodyUnderTheSameEventIdCannotReplaceTheOriginal() throws Exception {
        assertNotNull(inbox.accept(delivery(first)));
        var changed = first.deepCopy();
        changed.put("recordedBy", "other");
        assertThrows(RuntimeException.class, () -> inbox.accept(delivery(changed)));
        assertEquals(1, count());
    }

    @Test
    void nullableFactsRemainNullRatherThanDefaultZero() throws Exception {
        assertNotNull(inbox.accept(delivery(first)));
        var j =
                JSON.readTree(
                        db.queryForObject(
                                "SELECT payload FROM mom_mes.mes_inspection_event_inbox",
                                String.class));
        assertTrue(j.path("source").get("usableNum").isNull());
        assertTrue(j.path("source").get("handleNum").isNull());
        assertFalse(j.get("firstPassEligible").asBoolean());
    }

    @Test
    void saveZeroAndOuterRollbackNeverAcknowledgeAPermanentReceipt() throws Exception {
        fault.zero = true;
        assertThrows(RuntimeException.class, () -> inbox.accept(delivery(first)));
        assertEquals(0, count());
        fault.zero = false;
        var originalDelivery = delivery(first);
        new TransactionTemplate(tx)
                .execute(
                        status -> {
                            assertNotNull(inbox.accept(originalDelivery));
                            status.setRollbackOnly();
                            return null;
                        });
        assertEquals(0, count());
    }

    @Test
    void missingSchemaOrUnconfirmedCreationCannotBeSilentlyReceived() throws Exception {
        db.execute("DROP ALIAS mom_mes.mom_inspection_event_inbox_ready_v1");
        assertThrows(RuntimeException.class, () -> inbox.accept(delivery(first)));
        assertEquals(0, count());
        db.execute(
                "CREATE ALIAS mom_mes.mom_inspection_event_inbox_ready_v1 AS 'Integer"
                        + " ready(){return 1;}'");
        db.update("UPDATE mom_mes.mes_qms_inspection_command SET status='OUTBOUND_STARTED'");
        assertThrows(RuntimeException.class, () -> inbox.accept(delivery(first)));
        assertEquals(0, count());
    }

    @Test
    void aCorruptedEarlierReceiptPreventsBothNewAndDuplicateAcknowledgment() throws Exception {
        assertNotNull(inbox.accept(delivery(first)));
        db.update("UPDATE mom_mes.mes_inspection_event_inbox SET payload='{}'");
        assertThrows(RuntimeException.class, () -> inbox.accept(delivery(first)));
        assertThrows(RuntimeException.class, () -> inbox.accept(delivery(next(first))));
        assertEquals(1, count());
    }

    @Test
    void concurrentDuplicateDeliveriesKeepOnePermanentReceipt() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<MesInspectionEventReceiptDTO>> out = new ArrayList<>();
            for (int i = 0; i < 2; i++)
                out.add(
                        pool.submit(
                                () -> {
                                    TenantContextHolder.setTenantId(1L);
                                    FactoryContextHolder.setFactoryCode("F1");
                                    try {
                                        return inbox.accept(delivery(first));
                                    } finally {
                                        TenantContextHolder.clear();
                                        FactoryContextHolder.clear();
                                    }
                                }));
            var a = out.get(0).get(5, TimeUnit.SECONDS);
            var b = out.get(1).get(5, TimeUnit.SECONDS);
            assertNotNull(a);
            assertEquals(a, b);
            assertEquals(1, count());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aSecondJsonDocumentCannotHideBehindTheSignedFirstObject() throws Exception {
        var d = delivery(first);
        d.setPayload(d.getPayload() + " {}");
        d.setFingerprint(hash(d.getPayload()));
        resign(d);
        assertThrows(RuntimeException.class, () -> inbox.accept(d));
        assertEquals(0, count());
    }

    @Test
    void anUnconfiguredOrdinaryWriteKeyCannotAcceptAValidlyShapedEvent() throws Exception {
        ReflectionTestUtils.setField(inbox, "deliverySecret", "");
        assertThrows(RuntimeException.class, () -> inbox.accept(delivery(first)));
        assertEquals(0, count());
    }

    @Test
    void missingFrozenRootIdRemainsMissingAndUnapplied() throws Exception {
        String raw =
                db.queryForObject(
                                "SELECT command_snapshot FROM mom_mes.mes_qms_inspection_command"
                                        + " WHERE id=10",
                                String.class)
                        .replace("orderId:3:100\n", "orderId:-1:\n");
        db.update(
                "UPDATE mom_mes.mes_qms_inspection_command SET"
                        + " command_snapshot=?,command_fingerprint=? WHERE id=10",
                raw,
                hash(raw));
        ((ObjectNode) first.get("source")).putNull("orderId");
        ((ObjectNode) first.get("source")).put("sourceRequestFingerprint", hash(raw));
        var r = inbox.accept(delivery(first));
        assertNotNull(r);
        assertFalse(r.getApplied());
        assertTrue(
                JSON.readTree(
                                db.queryForObject(
                                        "SELECT payload FROM mom_mes.mes_inspection_event_inbox",
                                        String.class))
                        .path("source")
                        .get("orderId")
                        .isNull());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "eventId",
                "tenantId",
                "factoryCode",
                "payload",
                "fingerprint",
                "issuedAt",
                "nonce"
            })
    void everyTransportFieldBelongsToTheOrdinaryWriteSignature(String field) throws Exception {
        var d = delivery(first);
        switch (field) {
            case "eventId" -> d.setEventId(9002L);
            case "tenantId" -> d.setTenantId(2L);
            case "factoryCode" -> d.setFactoryCode("F2");
            case "payload" -> d.setPayload(d.getPayload() + " ");
            case "fingerprint" -> d.setFingerprint("a".repeat(64));
            case "issuedAt" -> d.setIssuedAt(d.getIssuedAt() + 1);
            case "nonce" -> d.setNonce("changed-test-nonce-12345");
            default -> fail(field);
        }
        assertFalse(d.signatureMatches(SECRET));
    }

    @Test
    void theRealHttpRouteRequiresBothInnerMarkerAndDedicatedSignature() throws Exception {
        var current =
                (HttpServletRequest)
                        Proxy.newProxyInstance(
                                HttpServletRequest.class.getClassLoader(),
                                new Class<?>[] {HttpServletRequest.class},
                                (p, m, a) ->
                                        m.invoke(
                                                ((ServletRequestAttributes)
                                                                RequestContextHolder
                                                                        .currentRequestAttributes())
                                                        .getRequest(),
                                                a));
        var proxy = new AspectJProxyFactory(new MesInspectionEventDeliveryController(inbox));
        proxy.setProxyTargetClass(true);
        proxy.addAspect(new MomSecurityInnerAspect(current));
        Object controller = proxy.getProxy();
        var mvc =
                MockMvcBuilders.standaloneSetup(controller)
                        .setControllerAdvice(
                                new MesReworkInspectionDeliveryBoundaryTest.DeniedAdvice())
                        .build();
        var d = delivery(first);
        String body = JSON.writeValueAsString(d);
        mvc.perform(
                        post("/mesTask/internal/inspection-event-delivery")
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isForbidden());
        assertEquals(0, count());
        d.setSignature(null);
        mvc.perform(
                        post("/mesTask/internal/inspection-event-delivery")
                                .header(SecurityConstants.FROM, SecurityConstants.FROM_IN)
                                .contentType("application/json")
                                .content(JSON.writeValueAsString(d)))
                .andExpect(status().isForbidden());
        assertEquals(0, count());
        mvc.perform(
                        post("/mesTask/internal/inspection-event-delivery")
                                .header(SecurityConstants.FROM, SecurityConstants.FROM_IN)
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("RECEIVED"))
                .andExpect(jsonPath("$.data.applied").value(false));
        assertEquals(1, count());
        RequestContextHolder.resetRequestAttributes();
    }

    int count() {
        return db.queryForObject(
                "SELECT COUNT(*) FROM mom_mes.mes_inspection_event_inbox", Integer.class);
    }

    static ObjectNode next(ObjectNode p) throws Exception {
        var n = p.deepCopy();
        n.put("eventId", Long.toString(Long.parseLong(p.get("eventId").asText()) + 1));
        n.put("historyId", Long.toString(Long.parseLong(p.get("historyId").asText()) + 1));
        n.put("sequenceNo", p.get("sequenceNo").asLong() + 1);
        n.put("previousEventId", p.get("eventId").asText());
        n.put("previousFingerprint", hash(JSON.writeValueAsString(p)));
        n.put("eventType", "APPEND");
        return n;
    }

    static MesInspectionEventDeliveryDTO delivery(ObjectNode j) throws Exception {
        String raw = JSON.writeValueAsString(j);
        var d =
                new MesInspectionEventDeliveryDTO()
                        .setEventId(Long.valueOf(j.get("eventId").asText()))
                        .setTenantId(1L)
                        .setFactoryCode("F1")
                        .setPayload(raw)
                        .setFingerprint(hash(raw))
                        .setIssuedAt(Instant.now().getEpochSecond())
                        .setNonce(UUID.randomUUID().toString());
        resign(d);
        return d;
    }

    static void resign(MesInspectionEventDeliveryDTO d) {
        d.setSignature(d.sign(SECRET));
    }

    static String hash(String s) throws Exception {
        return HexFormat.of()
                .formatHex(
                        MessageDigest.getInstance("SHA-256")
                                .digest(s.getBytes(StandardCharsets.UTF_8)));
    }

    @Intercepts(
            @Signature(
                    type = Executor.class,
                    method = "update",
                    args = {MappedStatement.class, Object.class}))
    static class WriteFault implements Interceptor {
        boolean zero;

        public Object intercept(Invocation i) throws Throwable {
            if (zero
                    && ((MappedStatement) i.getArgs()[0])
                            .getId()
                            .endsWith("MesInspectionEventInboxMapper.insert")) return 0;
            return i.proceed();
        }
    }
}
