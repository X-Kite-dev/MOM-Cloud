package com.kitex.mom.qms.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import cn.hutool.json.JSONUtil;

import com.kitex.mom.qms.dto.inspection.*;
import com.kitex.mom.qms.entity.*;
import com.kitex.mom.qms.service.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

/**
 * Actual creation/detail/sample/basis/history transactions, never the runtime kitex_mom database.
 */
class QmsInspectionBasisFreezeTransactionTest {
    private QmsProductInspectionIntegrityTest productFixture;
    private QmsProcedureInspectionIntegrityTest fixture;
    private InspectionProductTaskService service;
    private JdbcTemplate db;
    private QmsQualityStandardEntity standard;
    private QmsQualityStandardItemEntity item;
    private Object target;

    @BeforeEach
    void setup() throws Exception {
        productFixture = new QmsProductInspectionIntegrityTest();
        productFixture.setup();
        fixture =
                (QmsProcedureInspectionIntegrityTest)
                        ReflectionTestUtils.getField(productFixture, "fixture");
        service =
                (InspectionProductTaskService)
                        ReflectionTestUtils.getField(productFixture, "service");
        db = (JdbcTemplate) ReflectionTestUtils.getField(productFixture, "db");
        target = AopProxyUtils.getSingletonTarget(service);
        standard =
                (QmsQualityStandardEntity) ReflectionTestUtils.getField(productFixture, "standard");
        item = (QmsQualityStandardItemEntity) ReflectionTestUtils.getField(productFixture, "item");
    }

    @AfterEach
    void close() {
        if (productFixture != null) productFixture.close();
    }

    @ParameterizedTest
    @ValueSource(ints = {4, 1, 0})
    void pendingReplayMustMatchTheOriginalUncappedRequest(int changed) {
        standard.setInspectionMethod("PENDING");
        var c = command().setInspectionNum(5);
        service.create(c);
        assertThrows(RuntimeException.class, () -> service.create(c.setInspectionNum(changed)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"matName", "matBarcode", "matProduceDate"})
    void creationReplayIncludesBusinessInputsNotStoredOnTheMutableTask(String field) {
        var c = command();
        service.create(c);
        switch (field) {
            case "matName" -> c.setMatName("changed");
            case "matBarcode" -> c.setMatBarcode("SN-OTHER");
            case "matProduceDate" -> c.setMatProduceDate(new Date(1234567L));
            default -> throw new AssertionError(field);
        }
        assertThrows(RuntimeException.class, () -> service.create(c));
    }

    @Test
    void originalCreationCanReplayAfterAControlledAppend() {
        var c = command();
        service.create(c);
        long id = id(c);
        service.append(new InspectionAppendParam().setId(id).setNum(1));
        assertDoesNotThrow(() -> service.create(c));
        assertEquals(3, service.getById(id).getNum());
        assertEquals(2, bases(id));
    }

    @ParameterizedTest
    @ValueSource(strings = {"FIXED", "PENDING", "RANDOM"})
    void aFrozenPlanProvesTheOriginalCapAndFutureSamplingBands(String method) {
        standard.setInspectionMethod(method).setNum(2);
        var c = command().setInspectionNum(5);
        if ("RANDOM".equals(method)) {
            var strategy =
                    new QmsQualitySamplingStrategiesEntity()
                            .setFactoryCode("F1")
                            .setFrequency(
                                    List.of(
                                            new Frequency()
                                                    .setS(2)
                                                    .setE(5)
                                                    .setN(2)
                                                    .setAc(0)
                                                    .setRe(1),
                                            new Frequency().setS(6).setN(3).setAc(0).setRe(1)));
            strategy.setId(901L);
            strategy.setTenantId(1L);
            when(((QualitySamplingStrategiesService)
                                    ReflectionTestUtils.getField(fixture, "sampling"))
                            .getOne(any()))
                    .thenReturn(strategy);
        }
        service.create(c);
        long id = id(c);
        assertDoesNotThrow(() -> service.append(new InspectionAppendParam().setId(id).setNum(4)));
        int expected = "FIXED".equals(method) ? 2 : "PENDING".equals(method) ? 5 : 3;
        assertEquals(expected, service.getById(id).getInspectionNum());
        assertEquals(expected, detailInt(id, "total_num"));
        assertEquals(2, bases(id));
    }

    @Test
    void liveConfigurationChangesCannotRewriteAFrozenTaskOnAppend() {
        var c = command();
        service.create(c);
        long id = id(c);
        item.setUpperLimit(1D);
        standard.setPublished(0);
        assertDoesNotThrow(() -> service.append(new InspectionAppendParam().setId(id).setNum(1)));
        assertEquals(3, detailInt(id, "total_num"));
        assertEquals(
                10D,
                db.queryForObject(
                        "SELECT upper_limit FROM mom_qms.qms_inspection_product_task_detail WHERE"
                                + " task_id=? AND del_flag='0'",
                        Double.class,
                        id));
    }

    @Test
    void creationAtomicallyStoresItsOriginalRequestAndPlan() {
        var c = command();
        service.create(c);
        long id = id(c);
        assertEquals(1, bases(id));
        var row =
                db.queryForMap(
                        "SELECT payload,payload_fingerprint FROM mom_qms.qms_inspection_basis WHERE"
                                + " task_id=?",
                        id);
        var payload = JSONUtil.parseObj(row.get("payload").toString());
        assertEquals("CREATE", payload.getStr("operation"));
        assertEquals("FULL", payload.getJSONObject("plan").getStr("method"));
        assertTrue(payload.getStr("creationRequest").contains("inspectionNum"));
        assertEquals(64, row.get("payload_fingerprint").toString().length());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aRequiredBasisInsertReturningZeroRollsBackItsWholeOperation(boolean append) {
        var c = command();
        long id = 0;
        if (append) {
            service.create(c);
            id = id(c);
        }
        var fault = ReflectionTestUtils.getField(productFixture, "fault");
        ReflectionTestUtils.setField(fault, "lose", "QmsInspectionBasisMapper.insert");
        long task = id;
        assertThrows(
                RuntimeException.class,
                () -> {
                    if (append) service.append(new InspectionAppendParam().setId(task).setNum(1));
                    else service.create(c);
                });
        if (append) {
            assertEquals(2, service.getById(id).getNum());
            assertEquals(1, bases(id));
        } else {
            assertEquals(
                    1,
                    db.queryForObject(
                            "SELECT COUNT(*) FROM mom_qms.qms_inspection_product_task",
                            Integer.class));
            assertEquals(0, bases(0));
        }
    }

    @Test
    void frozenRequirementsCannotBeTamperedWithBeforeCollection() {
        var c = command();
        service.create(c);
        long id = id(c);
        db.update(
                "UPDATE mom_qms.qms_inspection_product_task_detail SET upper_limit=1 WHERE"
                        + " task_id=?",
                id);
        assertThrows(RuntimeException.class, () -> service.temporarilySave(sample(detail(id), 5D)));
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT COUNT(*) FROM mom_qms.qms_inspection_product_item_result_record",
                        Integer.class));
    }

    @Test
    void reconfigurationKeepsTheOriginalRequestAndCreatesANewBasisVersion() {
        var c = command();
        service.create(c);
        long id = id(c);
        standard.setId(2L);
        item.setStandardId(2L);
        service.update(command().setId(id).setStandardId(2L));
        assertDoesNotThrow(() -> service.create(c));
        assertEquals(2, bases(id));
        assertEquals(2L, service.getById(id).getStandardId());
    }

    @Test
    void legacyCreationCannotBeConfirmedFromOnlyTheCurrentTask() {
        var c = command();
        service.create(c);
        long id = id(c);
        if (bases(id) > 0)
            db.update("DELETE FROM mom_qms.qms_inspection_basis WHERE task_id=?", id);
        assertThrows(RuntimeException.class, () -> service.create(c));
    }

    @Test
    void existingTrustedV2FingerprintKeepsItsLegacyReplayContract() {
        standard.setInspectionType("PROCEDURE");
        var procedure =
                (InspectionProcedureTaskService) ReflectionTestUtils.getField(fixture, "service");
        var c =
                new InspectionProcedureCreateParam()
                        .setTaskCode("PT1")
                        .setSourceTaskCode("PT1")
                        .setSourceRequestId("MES_QMS_V2:BASIS")
                        .setLabelType("FIRST")
                        .setMatCode("MAT")
                        .setProcessCode("PROC")
                        .setStandardId(1L)
                        .setNum(2)
                        .setInspectionNum(2);
        c.setSourceRequestFingerprint(InspectionProcedureSourceContract.fingerprint(c));
        long id = procedure.create(c);
        if (bases(id) > 0)
            db.update(
                    "DELETE FROM mom_qms.qms_inspection_basis WHERE inspection_type='PROCEDURE' AND"
                            + " task_id=?",
                    id);
        assertEquals(id, procedure.create(c));
    }

    @ParameterizedTest
    @ValueSource(strings = {"OVERLAP", "THRESHOLD"})
    void frozenFuturePolicyMustAlsoBeUnambiguousAndValid(String invalid) {
        standard.setInspectionMethod("RANDOM");
        var bands = new ArrayList<Frequency>();
        bands.add(new Frequency().setS(2).setE(5).setN(2).setAc(0).setRe(1));
        if ("OVERLAP".equals(invalid)) {
            bands.add(new Frequency().setS(6).setE(15).setN(3).setAc(0).setRe(1));
            bands.add(new Frequency().setS(10).setN(2).setAc(0).setRe(1));
        } else bands.add(new Frequency().setS(6).setN(1).setAc(2).setRe(3));
        var strategy =
                new QmsQualitySamplingStrategiesEntity().setFactoryCode("F1").setFrequency(bands);
        strategy.setId(901L);
        strategy.setTenantId(1L);
        when(((QualitySamplingStrategiesService) ReflectionTestUtils.getField(fixture, "sampling"))
                        .getOne(any()))
                .thenReturn(strategy);
        assertThrows(RuntimeException.class, () -> service.create(command()));
    }

    @Test
    void readUsesTheExistingActionAndActualFactoryPermission() {
        var c = command();
        service.create(c);
        long id = id(c);
        var basis = basis();
        var permissions = mock(com.kitex.common.security.component.PermissionService.class);
        ReflectionTestUtils.setField(basis, "permissions", permissions);
        assertThrows(
                org.springframework.security.access.AccessDeniedException.class,
                () -> basis.view("PRODUCT", id));
        when(permissions.hasPermission("productTask:show")).thenReturn(true);
        ReflectionTestUtils.setField(fixture, "authorized", false);
        assertThrows(RuntimeException.class, () -> basis.view("PRODUCT", id));
        ReflectionTestUtils.setField(fixture, "authorized", true);
        var read = basis.view("PRODUCT", id);
        assertEquals("FROZEN", read.availability());
        assertEquals(id, read.taskId());
        assertEquals("F1", read.factoryCode());
        assertEquals(1, read.current().revision());
        db.update(
                "UPDATE mom_qms.qms_inspection_product_task SET factory_code='F2' WHERE id=?", id);
        assertThrows(RuntimeException.class, () -> basis.view("PRODUCT", id));
    }

    @Test
    void anOriginalLegacyTaskHasNoInventedPlan() {
        var basis = basis();
        var p = mock(com.kitex.common.security.component.PermissionService.class);
        when(p.hasPermission("productTask:show")).thenReturn(true);
        ReflectionTestUtils.setField(basis, "permissions", p);
        var view = basis.view("PRODUCT", 800L);
        assertEquals("LEGACY_UNAVAILABLE", view.availability());
        assertNull(view.current());
        assertNull(view.original());
    }

    @Test
    void missingDatabaseReadinessRollsBackCreationAndReturnsAnUpgradeReason() {
        db.execute("DROP ALIAS mom_qms.mom_inspection_basis_ready_v1");
        var failure = assertThrows(RuntimeException.class, () -> service.create(command()));
        assertTrue(failure.getMessage().contains("升级"));
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT COUNT(*) FROM mom_qms.qms_inspection_product_task", Integer.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PAYLOAD", "PREVIOUS", "DETAIL_ID"})
    void aCorruptBasisCannotBeUsedForCollection(String changed) {
        var c = command();
        service.create(c);
        long id = id(c);
        if ("PREVIOUS".equals(changed)) {
            service.append(new InspectionAppendParam().setId(id).setNum(1));
            db.update(
                    "UPDATE mom_qms.qms_inspection_basis SET previous_fingerprint=? WHERE task_id=?"
                            + " AND revision=2",
                    "0".repeat(64),
                    id);
        } else if ("PAYLOAD".equals(changed))
            db.update(
                    "UPDATE mom_qms.qms_inspection_basis SET payload=payload || ' ' WHERE"
                            + " task_id=?",
                    id);
        else
            db.update(
                    "UPDATE mom_qms.qms_inspection_product_task_detail SET standard_item_id=599"
                            + " WHERE task_id=?",
                    id);
        assertThrows(RuntimeException.class, () -> service.temporarilySave(sample(detail(id), 5D)));
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT COUNT(*) FROM mom_qms.qms_inspection_product_item_result_record",
                        Integer.class));
    }

    @Test
    void anExplicitLegacyReconfigurationDoesNotFabricateAnOriginalCreation() {
        var c = command();
        service.create(c);
        long id = id(c);
        db.update("DELETE FROM mom_qms.qms_inspection_basis WHERE task_id=?", id);
        service.update(command().setId(id));
        var p = mock(com.kitex.common.security.component.PermissionService.class);
        when(p.hasPermission("productTask:show")).thenReturn(true);
        ReflectionTestUtils.setField(basis(), "permissions", p);
        var view = basis().view("PRODUCT", id);
        assertEquals("FROZEN", view.availability());
        assertEquals("RECONFIGURE", view.current().operation());
        assertNull(view.original());
        assertThrows(RuntimeException.class, () -> service.create(c));
    }

    @Test
    void procedureRequestsAlsoKeepTheirUncappedOriginalIdentity() {
        standard.setInspectionType("PROCEDURE").setInspectionMethod("PENDING");
        var procedure =
                (InspectionProcedureTaskService) ReflectionTestUtils.getField(fixture, "service");
        var c =
                new InspectionProcedureCreateParam()
                        .setTaskCode("PTB")
                        .setSourceTaskCode("PTB")
                        .setSourceRequestId("LOCAL-PROCEDURE-BASIS")
                        .setLabelType("FIRST")
                        .setMatCode("MAT")
                        .setProcessCode("PROC")
                        .setStandardId(1L)
                        .setNum(2)
                        .setInspectionNum(5);
        long id = procedure.create(c);
        assertThrows(RuntimeException.class, () -> procedure.create(c.setInspectionNum(4)));
        c.setInspectionNum(5);
        procedure.append(new InspectionAppendParam().setId(id).setNum(4));
        assertEquals(5, procedure.getById(id).getInspectionNum());
        assertEquals(id, procedure.create(c));
        assertEquals(2, bases(id));
    }

    @Test
    void samplingConfigurationCannotChangeAFrozenFutureBand() {
        standard.setInspectionMethod("RANDOM");
        var strategy =
                new QmsQualitySamplingStrategiesEntity()
                        .setFactoryCode("F1")
                        .setFrequency(
                                List.of(
                                        new Frequency().setS(2).setE(5).setN(2).setAc(0).setRe(1),
                                        new Frequency().setS(6).setN(3).setAc(0).setRe(1)));
        strategy.setId(901L);
        strategy.setTenantId(1L);
        var sampling =
                (QualitySamplingStrategiesService)
                        ReflectionTestUtils.getField(fixture, "sampling");
        when(sampling.getOne(any())).thenReturn(strategy);
        var c = command();
        service.create(c);
        long id = id(c);
        strategy.setFrequency(List.of(new Frequency().setS(2).setN(1).setAc(0).setRe(1)));
        service.append(new InspectionAppendParam().setId(id).setNum(4));
        assertEquals(3, service.getById(id).getInspectionNum());
    }

    @Test
    void collectionAndAppendKeepTheActualSampleAndOneNewBasis() throws Exception {
        var c = command();
        service.create(c);
        long id = id(c), detail = detail(id);
        var auth =
                org.springframework.security.core.context.SecurityContextHolder.getContext()
                        .getAuthentication();
        var fault = ReflectionTestUtils.getField(productFixture, "fault");
        ReflectionTestUtils.setField(fault, "hold", true);
        var held =
                (java.util.concurrent.CountDownLatch) ReflectionTestUtils.getField(fault, "held");
        var release =
                (java.util.concurrent.CountDownLatch)
                        ReflectionTestUtils.getField(fault, "release");
        var second =
                (java.util.concurrent.CountDownLatch)
                        ReflectionTestUtils.getField(fault, "secondLock");
        var workers = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var a =
                    workers.submit(
                            () -> worker(auth, () -> service.temporarilySave(sample(detail, 5D))));
            assertTrue(held.await(3, java.util.concurrent.TimeUnit.SECONDS));
            var b =
                    workers.submit(
                            () ->
                                    worker(
                                            auth,
                                            () ->
                                                    service.append(
                                                            new InspectionAppendParam()
                                                                    .setId(id)
                                                                    .setNum(1))));
            assertTrue(second.await(3, java.util.concurrent.TimeUnit.SECONDS));
            release.countDown();
            assertTrue(a.get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(b.get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(2, bases(id));
            assertEquals(3, service.getById(id).getNum());
            assertEquals(1, detailInt(id, "finish_num"));
            assertEquals(2, detailInt(id, "wait_num"));
        } finally {
            release.countDown();
            workers.shutdownNow();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"product", "procedure"})
    void basisHttpRequiresTheExistingTaskShowAction(String type) throws Exception {
        var permissions = mock(com.kitex.common.security.component.PermissionService.class);
        var web = web(permissions);
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
            mvc.perform(
                            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                            "/inspection/basis/" + type)
                                    .param("taskId", "800"))
                    .andExpect(
                            org.springframework.test.web.servlet.result.MockMvcResultMatchers
                                    .status()
                                    .isForbidden());
        } finally {
            web.close();
        }
    }

    @Test
    void actualHttpKeepsLongIdentifiersPreciseInTheReadFrameAndSnapshots() throws Exception {
        var c = command();
        service.create(c);
        long id = id(c);
        var permissions = mock(com.kitex.common.security.component.PermissionService.class);
        when(permissions.hasPermission("productTask:show")).thenReturn(true);
        ReflectionTestUtils.setField(basis(), "permissions", permissions);
        var web = web(permissions);
        try {
            var mvc =
                    org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(
                                    web)
                            .build();
            mvc.perform(
                            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                            "/inspection/basis/product")
                                    .param("taskId", Long.toString(id)))
                    .andExpect(
                            org.springframework.test.web.servlet.result.MockMvcResultMatchers
                                    .status()
                                    .isOk())
                    .andExpect(
                            org.springframework.test.web.servlet.result.MockMvcResultMatchers
                                    .jsonPath("$.data.taskId")
                                    .value(Long.toString(id)))
                    .andExpect(
                            org.springframework.test.web.servlet.result.MockMvcResultMatchers
                                    .jsonPath("$.data.current.task.id")
                                    .value(Long.toString(id)))
                    .andExpect(
                            org.springframework.test.web.servlet.result.MockMvcResultMatchers
                                    .jsonPath("$.data.current.bindings[0].standardItemId")
                                    .value("501"));
        } finally {
            web.close();
        }
    }

    private org.springframework.web.context.support.GenericWebApplicationContext web(
            com.kitex.common.security.component.PermissionService permissions) {
        var web = new org.springframework.web.context.support.GenericWebApplicationContext();
        web.setServletContext(new org.springframework.mock.web.MockServletContext());
        org.springframework.context.annotation.AnnotationConfigUtils
                .registerAnnotationConfigProcessors(web);
        web.registerBean(QmsInspectionHistoryTransactionTest.WebConfiguration.class);
        web.registerBean(
                "pms",
                com.kitex.common.security.component.PermissionService.class,
                () -> permissions);
        web.registerBean(
                com.kitex.mom.qms.controller.QmsInspectionBasisController.class,
                () -> new com.kitex.mom.qms.controller.QmsInspectionBasisController(basis()));
        web.refresh();
        return web;
    }

    private boolean worker(org.springframework.security.core.Authentication auth, Runnable action) {
        com.kitex.common.data.tenant.TenantContextHolder.setTenantId(1L);
        com.kitex.common.data.orgFilter.FactoryContextHolder.setFactoryCode("F1");
        org.springframework.security.core.context.SecurityContextHolder.getContext()
                .setAuthentication(auth);
        try {
            action.run();
            return true;
        } finally {
            com.kitex.common.data.tenant.TenantContextHolder.clear();
            com.kitex.common.data.orgFilter.FactoryContextHolder.clear();
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    private QmsInspectionBasisService basis() {
        return (QmsInspectionBasisService) ReflectionTestUtils.getField(target, "inspectionBasis");
    }

    @Test
    void planConfigurationIsReadInAnIndependentRepeatableReadSnapshot() {
        var transactions =
                (org.springframework.transaction.PlatformTransactionManager)
                        ReflectionTestUtils.getField(
                                ReflectionTestUtils.getField(fixture, "historyFixture"),
                                "transactions");
        var outer = new org.springframework.transaction.support.TransactionTemplate(transactions);
        outer.execute(
                status -> {
                    var outerConnection =
                            org.springframework.jdbc.datasource.DataSourceUtils.getConnection(
                                    db.getDataSource());
                    when(((QualityStandardItemService)
                                            ReflectionTestUtils.getField(fixture, "items"))
                                    .list(
                                            any(
                                                    com.baomidou.mybatisplus.core.conditions.Wrapper
                                                            .class)))
                            .thenAnswer(
                                    call -> {
                                        assertTrue(
                                                org.springframework.transaction.support
                                                        .TransactionSynchronizationManager
                                                        .isCurrentTransactionReadOnly());
                                        assertEquals(
                                                org.springframework.transaction
                                                        .TransactionDefinition
                                                        .ISOLATION_REPEATABLE_READ,
                                                org.springframework.transaction.support
                                                        .TransactionSynchronizationManager
                                                        .getCurrentTransactionIsolationLevel());
                                        assertNotSame(
                                                outerConnection,
                                                org.springframework.jdbc.datasource.DataSourceUtils
                                                        .getConnection(db.getDataSource()));
                                        return List.of(item);
                                    });
                    service.create(command());
                    assertFalse(
                            org.springframework.transaction.support
                                    .TransactionSynchronizationManager
                                    .isCurrentTransactionReadOnly());
                    return null;
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"MATERIAL", "UNPUBLISHED", "ID"})
    void theConfigurationSnapshotMustStillBelongToTheSelectedPublishedMaterial(String drift) {
        var changed =
                cn.hutool.core.bean.BeanUtil.copyProperties(
                        standard, QmsQualityStandardEntity.class);
        switch (drift) {
            case "MATERIAL" -> changed.setMatCode("OTHER");
            case "UNPUBLISHED" -> changed.setPublished(0);
            case "ID" -> changed.setId(2L);
            default -> throw new AssertionError(drift);
        }
        when(((QualityStandardService) ReflectionTestUtils.getField(fixture, "standards"))
                        .getOne(any()))
                .thenReturn(standard, changed);
        assertThrows(RuntimeException.class, () -> service.create(command()));
    }

    @ParameterizedTest
    @CsvSource({
        "COLLECT,MISSING_FIRST",
        "COLLECT,CORRUPT_FIRST",
        "COLLECT,BROKEN_MIDDLE",
        "SUBMIT,CORRUPT_FIRST",
        "APPEND,MISSING_FIRST",
        "REPLAY,BROKEN_MIDDLE",
        "READ,BROKEN_MIDDLE"
    })
    void everyConsumedBasisMustValidateTheWholeOriginalChain(String operation, String damage) {
        var c = command();
        service.create(c);
        long id = id(c);
        for (int n = 0; n < 3; n++) service.append(new InspectionAppendParam().setId(id).setNum(1));
        if ("SUBMIT".equals(operation))
            for (int n = 0; n < 5; n++) service.temporarilySave(sample(detail(id), 5D));
        if ("MISSING_FIRST".equals(damage))
            db.update(
                    "DELETE FROM mom_qms.qms_inspection_basis WHERE task_id=? AND revision=1", id);
        else if ("CORRUPT_FIRST".equals(damage))
            db.update(
                    "UPDATE mom_qms.qms_inspection_basis SET payload=payload || ' ' WHERE task_id=?"
                            + " AND revision=1",
                    id);
        else
            db.update(
                    "UPDATE mom_qms.qms_inspection_basis SET previous_fingerprint=? WHERE task_id=?"
                            + " AND revision=2",
                    "0".repeat(64),
                    id);
        var permissions = mock(com.kitex.common.security.component.PermissionService.class);
        when(permissions.hasPermission("productTask:show")).thenReturn(true);
        ReflectionTestUtils.setField(basis(), "permissions", permissions);
        assertThrows(
                RuntimeException.class,
                () -> {
                    switch (operation) {
                        case "COLLECT" -> service.temporarilySave(sample(detail(id), 5D));
                        case "SUBMIT" -> service.submitInspectionResult(id);
                        case "APPEND" ->
                                service.append(new InspectionAppendParam().setId(id).setNum(1));
                        case "REPLAY" -> service.create(c);
                        case "READ" -> basis().view("PRODUCT", id);
                        default -> throw new AssertionError(operation);
                    }
                });
        assertEquals(5, service.getById(id).getNum());
        assertNull(service.getById(id).getResult());
        assertEquals(
                "SUBMIT".equals(operation) ? 5 : 0,
                db.queryForObject(
                        "SELECT COUNT(*) FROM mom_qms.qms_inspection_product_item_result_record",
                        Integer.class));
    }

    @Test
    void oversizedRestoredHistoryIsRejectedWithoutTransportingItsLargePayloads() {
        var c = command();
        service.create(c);
        long id = id(c);
        // H2 cannot clone a table with a public synonym while widening a column.
        db.execute("DROP SYNONYM public.qms_inspection_basis");
        db.execute("ALTER TABLE mom_qms.qms_inspection_basis ALTER COLUMN payload CLOB");
        db.execute("CREATE SYNONYM public.qms_inspection_basis FOR mom_qms.qms_inspection_basis");
        String payload = "\"" + "x".repeat(1_800_000) + "\"";
        for (int n = 0; n < 5; n++)
            db.update(
                    "INSERT INTO"
                        + " mom_qms.qms_inspection_basis(id,tenant_id,factory_code,inspection_type,task_id,revision,operation,previous_fingerprint,payload,payload_fingerprint,created_by,occurred_at)"
                        + " VALUES(?,1,'F1','PRODUCT',?,?,'APPEND',?,?,?,?,CURRENT_TIMESTAMP)",
                    10001L + n,
                    id,
                    n + 2,
                    "0".repeat(64),
                    payload,
                    QmsInspectionBasisJson.sha(payload),
                    2L);
        var mapper =
                (com.kitex.mom.qms.mapper.QmsInspectionBasisMapper)
                        ReflectionTestUtils.getField(basis(), "mapper");
        var rows = mapper.lineage(1L, "F1", "PRODUCT", id);
        assertEquals(6, rows.size());
        assertTrue(rows.stream().allMatch(row -> row.getPayload() == null));
        assertTrue(
                assertThrows(
                                RuntimeException.class,
                                () -> service.temporarilySave(sample(detail(id), 5D)))
                        .getMessage()
                        .contains("治理"));
    }

    @Test
    void tooManyRestoredRevisionsAreReadAsBoundedMetadataOnly() {
        var c = command();
        service.create(c);
        long id = id(c);
        db.update(
                "INSERT INTO"
                    + " mom_qms.qms_inspection_basis(id,tenant_id,factory_code,inspection_type,task_id,revision,operation,previous_fingerprint,payload,payload_fingerprint,created_by,occurred_at)"
                    + " SELECT x,1,'F1','PRODUCT',?,CAST(x-10000 AS"
                    + " integer),'APPEND',?,'{}',?,2,CURRENT_TIMESTAMP FROM"
                    + " SYSTEM_RANGE(10001,15001)",
                id,
                "0".repeat(64),
                QmsInspectionBasisJson.sha("{}"));
        var mapper =
                (com.kitex.mom.qms.mapper.QmsInspectionBasisMapper)
                        ReflectionTestUtils.getField(basis(), "mapper");
        var rows = mapper.lineage(1L, "F1", "PRODUCT", id);
        assertEquals(5001, rows.size());
        assertEquals(5002L, rows.get(0).getLineageRows());
        assertTrue(rows.stream().allMatch(row -> row.getPayload() == null));
        assertTrue(
                assertThrows(RuntimeException.class, () -> service.submitInspectionResult(id))
                        .getMessage()
                        .contains("治理"));
    }

    private InspectionProductCreateParam command() {
        return new InspectionProductCreateParam()
                .setSourceRequestId("LOCAL-" + UUID.randomUUID())
                .setSourceTaskCode("PT1")
                .setMatCode("MAT")
                .setProcessCode("PROC")
                .setStandardId(1L)
                .setNum(2)
                .setInspectionNum(2)
                .setOrderNo("MT1");
    }

    private long id(InspectionProductCreateParam c) {
        return db.queryForObject(
                "SELECT id FROM mom_qms.qms_inspection_product_task WHERE source_request_id=?",
                Long.class,
                c.getSourceRequestId());
    }

    private long detail(long id) {
        return db.queryForObject(
                "SELECT id FROM mom_qms.qms_inspection_product_task_detail WHERE task_id=? AND"
                        + " del_flag='0'",
                Long.class,
                id);
    }

    private int detailInt(long id, String field) {
        return db.queryForObject(
                "SELECT "
                        + field
                        + " FROM mom_qms.qms_inspection_product_task_detail WHERE task_id=? AND"
                        + " del_flag='0'",
                Integer.class,
                id);
    }

    private int bases(long id) {
        if (db.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.tables WHERE"
                                + " lower(table_schema)='mom_qms' AND"
                                + " lower(table_name)='qms_inspection_basis'",
                        Integer.class)
                == 0) return 0;
        return db.queryForObject(
                "SELECT COUNT(*) FROM mom_qms.qms_inspection_basis WHERE task_id=?",
                Integer.class,
                id);
    }

    private InspectionCheckResultParam sample(long id, Double v) {
        return new InspectionCheckResultParam()
                .setDetailId(id)
                .setRecord(new InspectionItemResultRecord().setDoubleValue(v));
    }
}
