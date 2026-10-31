package com.kitex.mom.mes.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.dto.MesInspectionProductionBasisDTO;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.mapper.*;
import com.kitex.mom.mes.service.*;
import com.kitex.mom.qms.dto.inspection.*;
import com.kitex.mom.qms.entity.*;

import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.*;

/**
 * Actual issue generation method and source INSERT; callback uses a separate real DB transaction.
 */
class MesInspectionIssueCommitTest {
    MesQualityGateSourceTransactionTest f;
    JdbcTemplate db;
    DataSourceTransactionManager tx;
    SqlSessionTemplate session;
    MesTaskServiceImpl issue;
    MesQualityGateService gate;
    MesInspectionGenerationDispatcher dispatcher;
    MesQmsInspectionCoordinator coordinator;
    MesQmsInspectionCommandService commands;
    MesProduceTaskEntity operation;
    MesInspectionGenRecordService records;
    MesInspectionProductionBasisService proof;
    int sends;
    boolean lose;

    @BeforeEach
    void setup() throws Exception {
        f = new MesQualityGateSourceTransactionTest();
        f.setup();
        db = (JdbcTemplate) ReflectionTestUtils.getField(f, "db");
        tx = (DataSourceTransactionManager) ReflectionTestUtils.getField(f, "tx");
        session = (SqlSessionTemplate) ReflectionTestUtils.getField(f, "session");
        gate = (MesQualityGateService) ReflectionTestUtils.getField(f, "gate");
        var target = AopProxyUtils.getSingletonTarget(gate);
        session.getConfiguration().addMapper(MesInspectionProductionBasisMapper.class);
        session.getConfiguration().addMapper(MesInspectionGenerationPendingMapper.class);
        proof =
                new MesInspectionProductionBasisService(
                        session.getMapper(MesQmsInspectionCommandMapper.class),
                        session.getMapper(MesInspectionProductionBasisMapper.class),
                        new MesEngineeringPackageService(
                                mock(
                                        com.kitex.mom.mdm.feign.RemoteMdmRouteEngineeringService
                                                .class)));
        dispatcher =
                new MesInspectionGenerationDispatcher(
                        session.getMapper(MesInspectionGenerationPendingMapper.class), gate);
        commands = mock(MesQmsInspectionCommandService.class);
        when(commands.register(any(), anyString())).thenAnswer(c -> register(c.getArgument(0)));
        coordinator =
                (MesQmsInspectionCoordinator)
                        ReflectionTestUtils.getField(target, "qmsInspectionCoordinator");
        doAnswer(
                        c -> {
                            InspectionProcedureCreateParam p = c.getArgument(0);
                            register(p);
                            sends++;
                            var independent = new TransactionTemplate(tx);
                            independent.setPropagationBehavior(3);
                            independent.executeWithoutResult(
                                    t -> {
                                        db.update(
                                                "UPDATE mom_mes.mes_qms_inspection_command SET"
                                                        + " status='OUTBOUND_STARTED' WHERE"
                                                        + " request_id=?",
                                                p.getSourceRequestId());
                                    });
                            independent.executeWithoutResult(
                                    t -> {
                                        assertEquals(1L, TenantContextHolder.getTenantId());
                                        assertEquals("F1", FactoryContextHolder.getFactoryCode());
                                        assertEquals(
                                                "LEGACY_UNAVAILABLE",
                                                proof.query(
                                                                new MesInspectionProductionBasisDTO
                                                                        .Query(
                                                                        p.getSourceRequestId(),
                                                                        p.getSourceTaskCode(),
                                                                        InspectionProcedureSourceContract
                                                                                .snapshot(p),
                                                                        p
                                                                                .getSourceRequestFingerprint()))
                                                        .availability());
                                    });
                            if (lose) throw new IllegalStateException("lost response");
                            independent.executeWithoutResult(
                                    t ->
                                            db.update(
                                                    "UPDATE mom_mes.mes_qms_inspection_command SET"
                                                        + " status='QMS_CONFIRMED',qms_task_id=800"
                                                        + " WHERE request_id=?",
                                                    p.getSourceRequestId()));
                            var q = new QmsInspectionProcedureTaskEntity();
                            BeanUtils.copyProperties(p, q);
                            q.setId(800L);
                            q.setTenantId(1L);
                            q.setFactoryCode("F1");
                            q.setStatus("WAIT");
                            q.setUsableNum(0);
                            q.setHandleNum(0);
                            ((Map<Long, QmsInspectionProcedureTaskEntity>)
                                            ReflectionTestUtils.getField(f, "sources"))
                                    .put(800L, q);
                            return 800L;
                        })
                .when(coordinator)
                .create(any(), anyString());
        var ctor = MesTaskServiceImpl.class.getConstructors()[0];
        Map<Class<?>, Object> deps = new HashMap<>();
        for (var t : ctor.getParameterTypes()) deps.put(t, mock(t));
        records =
                (MesInspectionGenRecordService)
                        ReflectionTestUtils.getField(target, "inspectionGenRecordService");
        deps.put(MesInspectionGenRecordService.class, records);
        deps.put(MesInspectionGenerationDispatcher.class, dispatcher);
        deps.put(MesQmsInspectionCommandService.class, commands);
        deps.put(MesQmsInspectionCoordinator.class, coordinator);
        for (var field :
                List.of(
                        "inspectionRuleService",
                        "remoteQualityStandardService",
                        "remoteClassesService")) {
            Object v = ReflectionTestUtils.getField(target, field);
            for (var t : ctor.getParameterTypes()) if (t.isInstance(v)) deps.put(t, v);
        }
        issue =
                (MesTaskServiceImpl)
                        ctor.newInstance(
                                Arrays.stream(ctor.getParameterTypes()).map(deps::get).toArray());
        ReflectionTestUtils.setField(issue, "baseMapper", session.getMapper(MesTaskMapper.class));
        db.update(
                "UPDATE mom_mes.mes_task SET"
                        + " mat_code='M1',process_route_id=101,process_route_code='ROUTE1' WHERE"
                        + " id=100");
        operation = new MesProduceTaskEntity();
        operation.setId(2L);
        operation.setTenantId(1L);
        operation.setFactoryCode("F1");
        operation.setCode("P2");
        operation.setTaskCode("T1");
        operation.setMatCode("M1");
        operation.setRouteCode("OP1");
        operation.setProcessRouteId(101L);
        operation.setProcessRouteCode("ROUTE1");
        operation.setIsInspection("1");
        operation.setNoticeNum(new BigDecimal("5"));
    }

    MesQmsInspectionCommandEntity register(InspectionProcedureCreateParam p) {
        p.setSourceRequestFingerprint(InspectionProcedureSourceContract.fingerprint(p));
        var it = new TransactionTemplate(tx);
        it.setPropagationBehavior(3);
        it.executeWithoutResult(
                t -> {
                    if (db.queryForObject(
                                    "SELECT count(*) FROM mom_mes.mes_qms_inspection_command WHERE"
                                            + " request_id=?",
                                    Integer.class,
                                    p.getSourceRequestId())
                            == 0)
                        db.update(
                                "INSERT INTO"
                                    + " mom_mes.mes_qms_inspection_command(id,tenant_id,factory_code,request_id,source_task_code,command_version,command_snapshot,command_fingerprint,status,del_flag)"
                                    + " VALUES(900,1,'F1',?,'P2',?,?,?,'REGISTERED','0')",
                                p.getSourceRequestId(),
                                InspectionProcedureSourceContract.VERSION,
                                InspectionProcedureSourceContract.snapshot(p),
                                p.getSourceRequestFingerprint());
                });
        return session.getMapper(MesQmsInspectionCommandMapper.class)
                .original(1L, p.getSourceRequestId());
    }

    void generate(boolean rollback) {
        new TransactionTemplate(tx)
                .executeWithoutResult(
                        t -> {
                            assertEquals(
                                    1,
                                    session.getMapper(MesProduceTaskMapper.class)
                                            .insert(operation));
                            ReflectionTestUtils.invokeMethod(
                                    issue, "genInspectionTask", List.of(operation), "M1");
                            if (rollback) t.setRollbackOnly();
                        });
    }

    @AfterEach
    void close() {
        if (f != null) f.clear();
    }

    @Test
    void initialIssueCreatesItsFirstTaskAfterTheSourceIsVisible() {
        generate(false);
        assertEquals(1, sends);
        assertEquals(
                "1",
                db.queryForObject(
                        "SELECT gen_flag FROM mom_mes.mes_inspection_gen_record WHERE"
                                + " produce_task_code='P2'",
                        String.class));
        assertEquals(
                800L,
                db.queryForObject(
                        "SELECT inspection_id FROM mom_mes.mes_inspection_gen_record WHERE"
                                + " produce_task_code='P2'",
                        Long.class));
    }

    @Test
    void rollbackNeverSendsInspectionAndLeavesNoRecoverableIntent() {
        generate(true);
        assertEquals(0, sends);
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT count(*) FROM mom_mes.mes_inspection_gen_record WHERE"
                                + " produce_task_code='P2'",
                        Integer.class));
        TenantContextHolder.clear();
        FactoryContextHolder.clear();
        assertEquals(
                0,
                new MesInspectionGenerationDispatcher(
                                session.getMapper(MesInspectionGenerationPendingMapper.class), gate)
                        .scan());
        assertEquals(0, sends);
    }

    @Test
    void aFreshDispatcherRecoversTheSameSavedOriginalAfterLostDelivery() {
        lose = true;
        generate(false);
        assertEquals(1, sends);
        String saved =
                db.queryForObject(
                        "SELECT command_snapshot FROM mom_mes.mes_qms_inspection_command WHERE"
                                + " id=900",
                        String.class);
        lose = false;
        ((QmsQualityStandardEntity) ReflectionTestUtils.getField(f, "currentStandard")).setId(999L);
        TenantContextHolder.clear();
        FactoryContextHolder.clear();
        assertEquals(
                1,
                new MesInspectionGenerationDispatcher(
                                session.getMapper(MesInspectionGenerationPendingMapper.class), gate)
                        .scan());
        assertEquals(2, sends);
        assertEquals(
                "1",
                db.queryForObject(
                        "SELECT gen_flag FROM mom_mes.mes_inspection_gen_record WHERE"
                                + " produce_task_code='P2'",
                        String.class));
        assertEquals(
                saved,
                db.queryForObject(
                        "SELECT command_snapshot FROM mom_mes.mes_qms_inspection_command WHERE"
                                + " id=900",
                        String.class));
        assertNull(TenantContextHolder.getTenantId());
        assertNull(FactoryContextHolder.getFactoryCode());
    }

    @Test
    void backgroundCreationAcknowledgementDoesNotTurnPendingIntoErrorWithoutAUserSession() {
        var target = AopProxyUtils.getSingletonTarget(gate);
        var remote =
                (com.kitex.mom.qms.api.feign.RemoteProcedureService)
                        ReflectionTestUtils.getField(target, "remoteProcedureService");
        doThrow(new IllegalStateException("public read requires staff token"))
                .when(remote)
                .getTaskByIds(anyList());
        generate(false);
        assertEquals(
                "1",
                db.queryForObject(
                        "SELECT gen_flag FROM mom_mes.mes_inspection_gen_record WHERE"
                                + " produce_task_code='P2'",
                        String.class));
        assertEquals(
                "PENDING",
                db.queryForObject(
                        "SELECT gate_status FROM mom_mes.mes_inspection_gen_record WHERE"
                                + " produce_task_code='P2'",
                        String.class));
    }

    @Test
    void postCommitDeliveryRestoresItsCapturedOrganizationAndThenTheCallerScope() {
        new TransactionTemplate(tx)
                .executeWithoutResult(
                        t -> {
                            assertEquals(
                                    1,
                                    session.getMapper(MesProduceTaskMapper.class)
                                            .insert(operation));
                            ReflectionTestUtils.invokeMethod(
                                    issue, "genInspectionTask", List.of(operation), "M1");
                            TenantContextHolder.setTenantId(2L);
                            FactoryContextHolder.setFactoryCode("F2");
                        });
        assertEquals(1, sends);
        assertEquals(
                "1",
                db.queryForObject(
                        "SELECT gen_flag FROM mom_mes.mes_inspection_gen_record WHERE"
                                + " produce_task_code='P2'",
                        String.class));
        assertEquals(2L, TenantContextHolder.getTenantId());
        assertEquals("F2", FactoryContextHolder.getFactoryCode());
    }

    @Test
    void requiredIntentInsertZeroRollsBackTheIssueWithoutSending() {
        ReflectionTestUtils.setField(ReflectionTestUtils.getField(f, "fault"), "failAt", 1);
        ReflectionTestUtils.setField(ReflectionTestUtils.getField(f, "fault"), "hits", 0);
        assertThrows(RuntimeException.class, () -> generate(false));
        assertEquals(0, sends);
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT count(*) FROM mom_mes.mes_produce_task WHERE code='P2'",
                        Integer.class));
    }
}
