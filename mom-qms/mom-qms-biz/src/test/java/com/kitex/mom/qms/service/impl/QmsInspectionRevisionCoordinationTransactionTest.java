package com.kitex.mom.qms.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.api.feign.RemoteMesTaskService;
import com.kitex.mom.mes.dto.*;
import com.kitex.mom.qms.dto.*;
import com.kitex.mom.qms.dto.inspection.QmsInspectionRevisionExecutionContract;
import com.kitex.mom.qms.entity.*;
import com.kitex.mom.qms.mapper.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;
import java.util.concurrent.*;

/** 真实QMS原件/事务/队列。MES网络端口模拟响应丢失，不作为真实跨服务或PG证明。 */
public class QmsInspectionRevisionCoordinationTransactionTest {
    static final String KEY = "revision-coordinate-test-write-secret-at-least32";
    QmsInspectionRevisionTransactionTest f;
    QmsInspectionRevisionCoordinationService service;
    QmsInspectionRevisionCoordinationWorker worker;
    QmsInspectionRevisionCoordinationDispatcher dispatcher;
    QmsInspectionRevisionCoordinationMapper mapper;
    QmsInspectionRevisionEntity request;
    RemoteMesTaskService mes;
    List<MesInspectionRevisionDeliveryDTO> sent = new ArrayList<>();
    Map<Long, MesInspectionRevisionAdmissionDTO> remote = new HashMap<>();
    String mode = "OK";

    @BeforeEach
    void setup() throws Exception {
        f = new QmsInspectionRevisionTransactionTest();
        f.setup(true);
        var basis = f.service.basis(700L);
        request =
                f.service.apply(
                        new QmsInspectionRevisionApply(
                                700L,
                                Long.valueOf(basis.eventId()),
                                basis.fingerprint(),
                                "UN_QUALIFIED",
                                0,
                                0,
                                "复核后整批判退"));
        f.actor(3);
        f.service.review(700L, request.getId(), f.review("APPROVED"));
        f.actor(4);
        mapper = f.f.session.getMapper(QmsInspectionRevisionCoordinationMapper.class);
        service =
                f.f.proxy(
                        new QmsInspectionRevisionCoordinationService(
                                mapper,
                                f.service,
                                (QmsInspectionHistorySupport)
                                        f.f.deps.get(QmsInspectionHistorySupport.class)));
        mes = mock(RemoteMesTaskService.class);
        when(mes.inspectionRevisionAdmission(any()))
                .thenAnswer(
                        a -> {
                            var d = (MesInspectionRevisionDeliveryDTO) a.getArgument(0);
                            assertTrue(d.signatureMatches(KEY));
                            sent.add(d);
                            var p = QmsInspectionRevisionExecutionContract.parse(d.getIntent());
                            var q = p.request();
                            var v = remote.get(d.getRequestId());
                            if (v == null) {
                                v =
                                        new MesInspectionRevisionAdmissionDTO()
                                                .setContractVersion("MES_REVISION_ADMISSION_V1")
                                                .setRequestId(d.getRequestId())
                                                .setTenantId(d.getTenantId())
                                                .setFactoryCode(d.getFactoryCode())
                                                .setTaskCode(
                                                        q.path("source")
                                                                .path("orderNo")
                                                                .textValue())
                                                .setProduceTaskCode(
                                                        q.path("source")
                                                                .path("sourceTaskCode")
                                                                .textValue())
                                                .setInspectionId(700L)
                                                .setBaseEventId(
                                                        Long.valueOf(
                                                                q.path("baseEventId").textValue()))
                                                .setBaseFingerprint(
                                                        q.path("baseFingerprint").textValue())
                                                .setIntentFingerprint(d.getIntentFingerprint());
                                remote.put(d.getRequestId(), v);
                            }
                            if ("CANCEL".equals(d.getOperation()))
                                v.setStatus("CANCELED")
                                        .setHeld(false)
                                        .setCanceledBy(d.getOperatorId())
                                        .setCancelReason(d.getReason())
                                        .setCanceledAt(
                                                v.getCanceledAt() == null
                                                        ? new Date()
                                                        : v.getCanceledAt());
                            else if (!"CANCELED".equals(v.getStatus()))
                                v.setStatus("RESERVED")
                                        .setHeld(true)
                                        .setGenRecordId(99L)
                                        .setAdmissionFingerprint("a".repeat(64))
                                        .setReservedAt(
                                                v.getReservedAt() == null
                                                        ? new Date()
                                                        : v.getReservedAt());
                            if ("LOST".equals(mode))
                                throw new IllegalStateException(
                                        "response lost after remote commit");
                            var copy = new MesInspectionRevisionAdmissionDTO();
                            org.springframework.beans.BeanUtils.copyProperties(v, copy);
                            switch (mode) {
                                case "TASK" -> copy.setInspectionId(701L);
                                case "REQUEST" -> copy.setRequestId(1L);
                                case "FACTORY" -> copy.setFactoryCode("OTHER");
                                case "TENANT" -> copy.setTenantId(2L);
                                case "INTENT" -> copy.setIntentFingerprint("b".repeat(64));
                                case "BASE" -> copy.setBaseFingerprint("b".repeat(64));
                                case "ROOT" -> copy.setTaskCode("OTHER");
                                case "PROCESS" -> copy.setProduceTaskCode("OTHER");
                                case "APPLIED" -> copy.setApplied(true);
                                case "HELD" -> copy.setHeld(false);
                                case "GEN" -> copy.setGenRecordId(null);
                                case "PROOF" -> copy.setAdmissionFingerprint(null);
                                case "FUTURE" ->
                                        copy.setReservedAt(
                                                new Date(System.currentTimeMillis() + 60000));
                                case "WRONG_CANCEL" -> copy.setCanceledBy(999L);
                                case "DROP_RESERVATION" ->
                                        copy.setGenRecordId(null)
                                                .setReservedAt(null)
                                                .setAdmissionFingerprint(null);
                                default -> {}
                            }
                            if (mode.startsWith("WIRE_")) {
                                var json = new com.fasterxml.jackson.databind.ObjectMapper();
                                var node =
                                        (com.fasterxml.jackson.databind.node.ObjectNode)
                                                json.valueToTree(copy);
                                var parts = mode.split("_");
                                String field = parts[2].toLowerCase(java.util.Locale.ROOT);
                                boolean value =
                                        "held".equals(field) ? copy.isHeld() : copy.isApplied();
                                switch (parts[3]) {
                                    case "MISSING" -> node.remove(field);
                                    case "NULL" -> node.putNull(field);
                                    case "STRING" -> node.put(field, Boolean.toString(value));
                                    case "NUMBER" -> node.put(field, value ? 1 : 0);
                                    default -> throw new IllegalArgumentException(mode);
                                }
                                return R.ok(
                                        json.readValue(
                                                json.writeValueAsBytes(node),
                                                MesInspectionRevisionAdmissionDTO.class));
                            }
                            return "FAIL_WITH_DATA".equals(mode) ? R.failed(copy) : R.ok(copy);
                        });
        worker = f.f.proxy(new QmsInspectionRevisionCoordinationWorker(mapper, mes));
        ReflectionTestUtils.setField(worker, "deliverySecret", KEY);
        dispatcher = new QmsInspectionRevisionCoordinationDispatcher(mapper, worker);
    }

    @AfterEach
    void clear() {
        if (f != null) f.clear();
    }

    QmsInspectionRevisionCoordinationDTO prepare() {
        return service.prepare(700L, request.getId());
    }

    void due() {
        f.f.db.update(
                "UPDATE mom_qms.qms_inspection_revision_delivery SET"
                        + " next_attempt_at=CURRENT_TIMESTAMP");
    }

    int count(String table) {
        return f.count(table);
    }

    @Test
    void preparePersistsExactIntentAndQueueWithoutNetworkOrChangingQuality() {
        var a = prepare();
        var b = prepare();
        assertEquals(a.intentFingerprint(), b.intentFingerprint());
        assertEquals("PENDING", a.status());
        assertEquals(1, count("qms_inspection_revision_execution"));
        assertEquals(1, count("qms_inspection_revision_delivery"));
        assertEquals(0, sent.size());
        assertEquals(
                "QUALIFIED",
                f.f.db.queryForObject(
                        "SELECT result FROM mom_qms.qms_inspection_procedure_task WHERE id=700",
                        String.class));
    }

    @Test
    void deliveryAndReceiptAreDurableAndNotActualExecution() {
        prepare();
        assertTrue(worker.deliver(request.getId()));
        var a = service.state(700L, request.getId());
        assertEquals("RESERVED", a.status());
        assertFalse(a.applied());
        assertTrue(a.held());
        assertEquals(1, count("qms_inspection_revision_receipt"));
        assertFalse(worker.deliver(request.getId()));
    }

    @Test
    void unknownRemoteCommitIsRecoveredWithIdenticalIntent() {
        prepare();
        mode = "LOST";
        assertFalse(worker.deliver(request.getId()));
        assertEquals("RETRY_WAIT", service.state(700L, request.getId()).status());
        mode = "OK";
        due();
        assertTrue(worker.deliver(request.getId()));
        assertEquals(sent.get(0).getIntent(), sent.get(1).getIntent());
        assertEquals(1, remote.size());
        assertEquals(1, count("qms_inspection_revision_receipt"));
    }

    @Test
    void cancellationFirstIsConfirmedBeforeNewApplicationIsAllowed() {
        prepare();
        service.cancel(700L, request.getId(), new QmsInspectionRevisionCancelCommand("取消本次执行准备"));
        var before = f.service.basis(700L);
        var ex =
                assertThrows(
                        RuntimeException.class,
                        () ->
                                f.service.apply(
                                        new QmsInspectionRevisionApply(
                                                700L,
                                                Long.valueOf(before.eventId()),
                                                before.fingerprint(),
                                                "UN_QUALIFIED",
                                                0,
                                                0,
                                                "重新申请")));
        assertTrue(ex.getMessage().contains("已有待审核"));
        assertTrue(worker.deliver(request.getId()));
        assertEquals("CANCEL", sent.get(0).getOperation());
        assertEquals("CANCELED", service.state(700L, request.getId()).status());
        assertNull(service.state(700L, request.getId()).receipt().genRecordId());
        var b = f.service.basis(700L);
        assertNotNull(
                f.service.apply(
                        new QmsInspectionRevisionApply(
                                700L,
                                Long.valueOf(b.eventId()),
                                b.fingerprint(),
                                "UN_QUALIFIED",
                                0,
                                0,
                                "重新申请")));
    }

    @Test
    void unknownReservationCanBeCanceledWithoutAssumingItNeverHeld() {
        prepare();
        mode = "LOST";
        worker.deliver(request.getId());
        service.cancel(700L, request.getId(), new QmsInspectionRevisionCancelCommand("中止未知提交"));
        mode = "OK";
        assertTrue(worker.deliver(request.getId()));
        assertFalse(service.state(700L, request.getId()).held());
        assertEquals("CANCELED", service.state(700L, request.getId()).status());
        assertEquals("CANCEL", sent.get(1).getOperation());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "TASK",
                "REQUEST",
                "FACTORY",
                "TENANT",
                "INTENT",
                "BASE",
                "ROOT",
                "PROCESS",
                "APPLIED",
                "HELD",
                "GEN",
                "PROOF",
                "FUTURE",
                "FAIL_WITH_DATA"
            })
    void mismatchedReceiptNeverConfirmsReservation(String bad) {
        prepare();
        mode = bad;
        assertFalse(worker.deliver(request.getId()));
        assertEquals(0, count("qms_inspection_revision_receipt"));
        assertEquals("RETRY_WAIT", service.state(700L, request.getId()).status());
    }

    @Test
    void cancellationAcknowledgmentMustMatchItsActorAndReason() {
        prepare();
        service.cancel(700L, request.getId(), new QmsInspectionRevisionCancelCommand("取消准备"));
        mode = "WRONG_CANCEL";
        assertFalse(worker.deliver(request.getId()));
        assertEquals(0, count("qms_inspection_revision_receipt"));
    }

    @Test
    void storedReceiptCorruptionCannotBeDisplayedOrUsedAsTerminal() {
        prepare();
        worker.deliver(request.getId());
        f.f.db.update("UPDATE mom_qms.qms_inspection_revision_receipt SET payload=payload||' '");
        assertThrows(RuntimeException.class, () -> service.state(700L, request.getId()));
    }

    @Test
    void lossOfFactoryPermissionAndWrongScopeFailClosed() {
        f.allowed = false;
        assertThrows(RuntimeException.class, this::prepare);
        f.allowed = true;
        prepare();
        FactoryContextHolder.setFactoryCode("OTHER");
        assertThrows(RuntimeException.class, () -> service.state(700L, request.getId()));
        FactoryContextHolder.setFactoryCode("F1");
        TenantContextHolder.setTenantSkip();
        assertThrows(RuntimeException.class, () -> worker.deliver(request.getId()));
    }

    @Test
    void differentExecutorCannotReplaceFrozenPreparation() {
        prepare();
        f.actor(5);
        assertThrows(RuntimeException.class, this::prepare);
    }

    @Test
    void staleCurrentSourceCannotBePrepared() {
        f.f.db.update("UPDATE mom_qms.qms_inspection_procedure_task SET handle_num=NULL");
        assertThrows(RuntimeException.class, this::prepare);
        assertEquals(0, count("qms_inspection_revision_execution"));
    }

    @Test
    void exactCancelIsIdempotentButActorOrReasonCannotBeReplaced() {
        prepare();
        service.cancel(700L, request.getId(), new QmsInspectionRevisionCancelCommand("取消准备"));
        service.cancel(700L, request.getId(), new QmsInspectionRevisionCancelCommand("取消准备"));
        assertEquals(1, count("qms_inspection_revision_cancel"));
        assertThrows(
                RuntimeException.class,
                () ->
                        service.cancel(
                                700L,
                                request.getId(),
                                new QmsInspectionRevisionCancelCommand("换原因")));
    }

    @Test
    void attentionCanOnlyRequeueSameOperationAndIntent() {
        var p = prepare();
        ReflectionTestUtils.setField(worker, "deliverySecret", "");
        worker.deliver(request.getId());
        assertEquals("ATTENTION", service.state(700L, request.getId()).status());
        service.retry(700L, request.getId());
        assertEquals("PENDING", service.state(700L, request.getId()).status());
        assertEquals(
                p.intentFingerprint(), service.state(700L, request.getId()).intentFingerprint());
    }

    @Test
    void independentScanRestoresScopeAndDoesNotFabricateLogin() {
        prepare();
        SecurityContextHolder.clearContext();
        TenantContextHolder.clear();
        FactoryContextHolder.clear();
        TenantContextHolder.setTenantId(9L);
        FactoryContextHolder.setFactoryCode("ORIGINAL");
        assertEquals(1, dispatcher.scan());
        assertEquals(9L, TenantContextHolder.getTenantId());
        assertEquals("ORIGINAL", FactoryContextHolder.getFactoryCode());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void changedApprovalOriginalCannotBeSentFromFrozenIntentAlone() {
        prepare();
        f.f.db.update(
                "UPDATE mom_qms.qms_inspection_revision_review SET note='changed' WHERE id=?",
                request.getId());
        assertFalse(worker.deliver(request.getId()));
        assertEquals(0, sent.size());
        assertThrows(RuntimeException.class, () -> service.state(700L, request.getId()));
    }

    @Test
    void cancelAcknowledgmentCannotForgetPreviouslyConfirmedReservation() {
        prepare();
        worker.deliver(request.getId());
        service.cancel(700L, request.getId(), new QmsInspectionRevisionCancelCommand("取消准备"));
        mode = "DROP_RESERVATION";
        assertFalse(worker.deliver(request.getId()));
        assertEquals(1, count("qms_inspection_revision_receipt"));
    }

    @Test
    void scanWithUserIsRejected() {
        prepare();
        assertThrows(RuntimeException.class, () -> dispatcher.scan());
    }

    @Test
    void pendingUnknownReceiptNeverSaysDefinitelyUnheld() {
        prepare();
        mode = "LOST";
        worker.deliver(request.getId());
        assertNull(service.state(700L, request.getId()).held());
    }

    @ParameterizedTest
    @ValueSource(strings = {"DELIVERY", "CANCEL", "RECEIPT"})
    void insertZeroAfterRealInsertRollsBackLocalFactsAndCanRecover(String kind) {
        var fault = new InsertZero();
        f.f.session.getConfiguration().addInterceptor(fault);
        if ("DELIVERY".equals(kind)) {
            fault.statement = "insertDelivery";
            assertThrows(RuntimeException.class, this::prepare);
            assertEquals(0, count("qms_inspection_revision_execution"));
            assertEquals(0, count("qms_inspection_revision_delivery"));
        }
        if ("CANCEL".equals(kind)) {
            prepare();
            fault.statement = "insertCancel";
            assertThrows(
                    RuntimeException.class,
                    () ->
                            service.cancel(
                                    700L,
                                    request.getId(),
                                    new QmsInspectionRevisionCancelCommand("取消准备")));
            assertEquals(0, count("qms_inspection_revision_cancel"));
            assertEquals("RESERVE", service.state(700L, request.getId()).operation());
        }
        if ("RECEIPT".equals(kind)) {
            prepare();
            fault.statement = "insertReceipt";
            assertThrows(RuntimeException.class, () -> worker.deliver(request.getId()));
            assertEquals(0, count("qms_inspection_revision_receipt"));
            assertEquals("PENDING", service.state(700L, request.getId()).status());
            assertTrue(remote.get(request.getId()).isHeld());
            fault.statement = "";
            assertTrue(worker.deliver(request.getId()));
            assertEquals(1, count("qms_inspection_revision_receipt"));
            assertEquals(sent.get(0).getIntent(), sent.get(1).getIntent());
        }
    }

    @org.apache.ibatis.plugin.Intercepts(
            @org.apache.ibatis.plugin.Signature(
                    type = org.apache.ibatis.executor.Executor.class,
                    method = "update",
                    args = {org.apache.ibatis.mapping.MappedStatement.class, Object.class}))
    static class InsertZero implements org.apache.ibatis.plugin.Interceptor {
        String statement = "";

        public Object intercept(org.apache.ibatis.plugin.Invocation call) throws Throwable {
            Object n = call.proceed();
            return ((org.apache.ibatis.mapping.MappedStatement) call.getArgs()[0])
                                    .getId()
                                    .endsWith("." + statement)
                            && !statement.isEmpty()
                    ? 0
                    : n;
        }
    }

    @Test
    void networkWaitDoesNotHoldQmsBusinessParentLock() throws Exception {
        prepare();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(
                        a -> {
                            entered.countDown();
                            assertTrue(release.await(5, TimeUnit.SECONDS));
                            throw new IllegalStateException("simulated network wait");
                        })
                .when(mes)
                .inspectionRevisionAdmission(any());
        var pool = Executors.newSingleThreadExecutor();
        try {
            var future =
                    pool.submit(
                            () -> {
                                TenantContextHolder.setTenantId(1L);
                                FactoryContextHolder.setFactoryCode("F1");
                                try {
                                    return worker.deliver(request.getId());
                                } finally {
                                    TenantContextHolder.clear();
                                    FactoryContextHolder.clear();
                                    SecurityContextHolder.clearContext();
                                }
                            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            new org.springframework.transaction.support.TransactionTemplate(f.f.tx)
                    .execute(
                            status -> {
                                assertEquals(
                                        700L,
                                        f.f.db.queryForObject(
                                                "SELECT id FROM"
                                                    + " mom_qms.qms_inspection_procedure_task WHERE"
                                                    + " id=700 FOR UPDATE",
                                                Long.class));
                                return null;
                            });
            release.countDown();
            assertFalse(future.get(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void damagedCancelReceiptDoesNotEndUnresolvedRequest() {
        prepare();
        service.cancel(700L, request.getId(), new QmsInspectionRevisionCancelCommand("取消准备"));
        worker.deliver(request.getId());
        f.f.db.update("UPDATE mom_qms.qms_inspection_revision_receipt SET payload=payload||' '");
        var b = f.service.basis(700L);
        assertThrows(
                RuntimeException.class,
                () ->
                        f.service.apply(
                                new QmsInspectionRevisionApply(
                                        700L,
                                        Long.valueOf(b.eventId()),
                                        b.fingerprint(),
                                        "UN_QUALIFIED",
                                        0,
                                        0,
                                        "重新申请")));
    }

    @Test
    void automaticRetriesStopAfterEightAttempts() {
        prepare();
        mode = "LOST";
        for (int i = 0; i < 8; i++) {
            due();
            assertFalse(worker.deliver(request.getId()));
        }
        assertEquals("ATTENTION", service.state(700L, request.getId()).status());
        due();
        assertFalse(worker.deliver(request.getId()));
        assertEquals(8, sent.size());
        service.retry(700L, request.getId());
        assertEquals(0, service.state(700L, request.getId()).attemptCount());
    }

    @Test
    void mvcPermissionsAreIndependentAndIdsAreText() throws Exception {
        var web = new org.springframework.web.context.support.GenericWebApplicationContext();
        web.setServletContext(new org.springframework.mock.web.MockServletContext());
        org.springframework.context.annotation.AnnotationConfigUtils
                .registerAnnotationConfigProcessors(web);
        web.registerBean(QmsInspectionHistoryTransactionTest.WebConfiguration.class);
        var permissions = new Permissions();
        web.registerBean("pms", Permissions.class, () -> permissions);
        web.registerBean(
                com.kitex.mom.qms.controller.QmsInspectionRevisionCoordinationController.class,
                () ->
                        new com.kitex.mom.qms.controller
                                .QmsInspectionRevisionCoordinationController(service, worker));
        web.refresh();
        try {
            var mvc =
                    org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(
                                    web)
                            .addFilters(
                                    new org.springframework.security.web.access
                                            .ExceptionTranslationFilter(
                                            new org.springframework.security.web.authentication
                                                    .Http403ForbiddenEntryPoint()))
                            .build();
            String base =
                    "/inspection/procedure/revisions/700/" + request.getId() + "/coordination";
            mvc.perform(
                            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                    base))
                    .andExpect(
                            org.springframework.test.web.servlet.result.MockMvcResultMatchers
                                    .status()
                                    .isForbidden());
            permissions.allowed.add("procedureTask:history");
            mvc.perform(
                            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                    base))
                    .andExpect(
                            org.springframework.test.web.servlet.result.MockMvcResultMatchers
                                    .jsonPath("$.data.status")
                                    .value("ABSENT"));
            mvc.perform(
                            org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                    .post(base + "/prepare"))
                    .andExpect(
                            org.springframework.test.web.servlet.result.MockMvcResultMatchers
                                    .status()
                                    .isForbidden());
            permissions.allowed.add("procedureTask:revision:prepare");
            mvc.perform(
                            org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                    .post(base + "/prepare"))
                    .andExpect(
                            org.springframework.test.web.servlet.result.MockMvcResultMatchers
                                    .jsonPath("$.data.status")
                                    .value("RESERVED"))
                    .andExpect(
                            org.springframework.test.web.servlet.result.MockMvcResultMatchers
                                    .jsonPath("$.data.requestId")
                                    .value(request.getId().toString()))
                    .andExpect(
                            org.springframework.test.web.servlet.result.MockMvcResultMatchers
                                    .jsonPath("$.data.receipt.genRecordId")
                                    .value("99"))
                    .andExpect(
                            org.springframework.test.web.servlet.result.MockMvcResultMatchers
                                    .jsonPath("$.data.receipt.reservedAt")
                                    .value(org.hamcrest.Matchers.endsWith("Z")));
            mvc.perform(
                            org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                    .post(base + "/cancel")
                                    .contentType("application/json")
                                    .content("{\"reason\":\"取消准备\"}"))
                    .andExpect(
                            org.springframework.test.web.servlet.result.MockMvcResultMatchers
                                    .status()
                                    .isForbidden());
            permissions.allowed.add("procedureTask:revision:cancel");
            mvc.perform(
                            org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                    .post(base + "/cancel")
                                    .contentType("application/json")
                                    .content("{\"reason\":\"取消准备\"}"))
                    .andExpect(
                            org.springframework.test.web.servlet.result.MockMvcResultMatchers
                                    .jsonPath("$.data.status")
                                    .value("CANCELED"));
            mvc.perform(
                            org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                    .post(base + "/retry"))
                    .andExpect(
                            org.springframework.test.web.servlet.result.MockMvcResultMatchers
                                    .status()
                                    .isForbidden());
        } finally {
            web.close();
        }
    }

    public static class Permissions {
        final Set<String> allowed = new HashSet<>();

        public boolean hasPermission(String permission) {
            return allowed.contains(permission);
        }
    }

    @Test
    void revisionBasisRequiresTrustedCoordinationTerminalStructure() {
        f.f.db.update("UPDATE qms_coordination_installation SET version=0");
        assertThrows(RuntimeException.class, () -> f.service.basis(700L));
    }

    @Test
    void preparedCancellationAndStatusSurviveBusinessTaskRemoval() {
        prepare();
        worker.deliver(request.getId());
        f.f.db.update("UPDATE mom_qms.qms_inspection_procedure_task SET del_flag='1' WHERE id=700");
        assertDoesNotThrow(
                () ->
                        service.cancel(
                                700L,
                                request.getId(),
                                new QmsInspectionRevisionCancelCommand("停止已撤除任务的执行准备")));
        assertTrue(worker.deliver(request.getId()));
        assertEquals("CANCELED", service.state(700L, request.getId()).status());
    }

    @Test
    void confirmedCancellationIsVisibleInOriginalRevisionHistory() {
        prepare();
        service.cancel(700L, request.getId(), new QmsInspectionRevisionCancelCommand("取消准备"));
        worker.deliver(request.getId());
        assertEquals("CANCELED_PREPARATION", f.service.page(700L, 0L, 20).items().get(0).state());
        assertEquals("CANCELED_PREPARATION", f.service.detail(700L, request.getId()).state());
        f.f.db.update("UPDATE mom_qms.qms_inspection_revision_receipt SET payload=payload||' '");
        assertThrows(RuntimeException.class, () -> f.service.page(700L, 0L, 20));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "WIRE_CANCEL_HELD_MISSING",
                "WIRE_CANCEL_HELD_NULL",
                "WIRE_CANCEL_HELD_STRING",
                "WIRE_CANCEL_HELD_NUMBER",
                "WIRE_CANCEL_APPLIED_MISSING",
                "WIRE_CANCEL_APPLIED_NULL",
                "WIRE_CANCEL_APPLIED_STRING",
                "WIRE_CANCEL_APPLIED_NUMBER",
                "WIRE_RESERVE_APPLIED_MISSING",
                "WIRE_RESERVE_APPLIED_NULL",
                "WIRE_RESERVE_APPLIED_STRING",
                "WIRE_RESERVE_APPLIED_NUMBER",
                "WIRE_RESERVE_HELD_STRING",
                "WIRE_RESERVE_HELD_NUMBER"
            })
    void actualJsonRequiresExplicitBooleanReceiptDeclarations(String wire) {
        prepare();
        if (wire.startsWith("WIRE_CANCEL"))
            service.cancel(700L, request.getId(), new QmsInspectionRevisionCancelCommand("取消准备"));
        mode = wire;
        assertFalse(worker.deliver(request.getId()));
        assertEquals(0, count("qms_inspection_revision_receipt"));
        assertEquals("RETRY_WAIT", service.state(700L, request.getId()).status());
        assertNull(service.state(700L, request.getId()).held());
        assertEquals(1, f.mapper.unresolved(1L, "F1", 700L));
    }

    @Test
    void mdmAuthorizationCompletesBeforeAnyBusinessParentLock() {
        var locked = new java.util.concurrent.atomic.AtomicInteger();
        var free = new java.util.concurrent.atomic.AtomicInteger();
        var factories =
                (com.kitex.mom.mdm.feign.RemoteMdmFactoryAccessService)
                        f.f.deps.get(com.kitex.mom.mdm.feign.RemoteMdmFactoryAccessService.class);
        doAnswer(
                        a -> {
                            var check =
                                    new org.springframework.transaction.support.TransactionTemplate(
                                            f.f.tx);
                            check.setPropagationBehavior(
                                    org.springframework.transaction.TransactionDefinition
                                            .PROPAGATION_REQUIRES_NEW);
                            try {
                                check.execute(
                                        status -> {
                                            assertEquals(
                                                    700L,
                                                    f.f.db.queryForObject(
                                                            "SELECT id FROM"
                                                                + " mom_qms.qms_inspection_procedure_task"
                                                                + " WHERE id=700 FOR UPDATE NOWAIT",
                                                            Long.class));
                                            return null;
                                        });
                                free.incrementAndGet();
                            } catch (org.springframework.dao.DataAccessException ex) {
                                String text =
                                        ex.getMostSpecificCause()
                                                .getMessage()
                                                .toLowerCase(java.util.Locale.ROOT);
                                assertTrue(text.contains("lock") || text.contains("timeout"), text);
                                locked.incrementAndGet();
                            }
                            return R.ok(true);
                        })
                .when(factories)
                .check(any());
        prepare();
        assertTrue(free.get() > 0);
        assertEquals(0, locked.get(), "MDM网络授权时业务父行必须仍可被另一事务取得");
    }
}
