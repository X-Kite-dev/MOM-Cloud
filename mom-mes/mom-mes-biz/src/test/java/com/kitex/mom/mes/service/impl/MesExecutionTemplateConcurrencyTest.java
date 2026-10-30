package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.dto.MesIdentifierNodePolicyDTO;
import com.kitex.mom.mes.entity.MesExecutionTemplateEntity;
import com.kitex.mom.mes.mapper.MesExecutionTemplateMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.io.Serializable;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Real Spring/JDBC transactions. Row/advisory mapper calls are adapted to H2 row locks; PostgreSQL needs field
 * validation.
 */
class MesExecutionTemplateConcurrencyTest {
    private final CountDownLatch retirementWritten = new CountDownLatch(1), continueSwitch = new CountDownLatch(1);
    private final CyclicBarrier conflictObservation = new CyclicBarrier(2);
    private JdbcTemplate db;
    private MesExecutionTemplateServiceImpl service;
    private boolean concurrencyProbe, failAfterWrite;
    private int writesUntilFailure;
    private int writesUntilFalse;
    private boolean pauseRetirement;
    private MesPhysicalTransformPolicyValidator physical;
    private DataSourceTransactionManager transactions;

    @BeforeEach
    void setup() {
        var ds = new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "sa", "");
        ds.setDriverClassName("org.h2.Driver");
        db = new JdbcTemplate(ds);
        db.execute("create table template(id bigint primary key, code varchar, state "
                + "varchar, revision bigint, remark varchar, priority int default 0,"
                + " version_no int default 1, material_code varchar, route_code "
                + "varchar, process_route_code varchar, route_node_code varchar, "
                + "workshop_code varchar, worksite_code varchar, effective_from "
                + "timestamp, effective_to timestamp, unique(code,version_no))");
        db.execute("create table publication_scope(id bigint primary key)");
        db.update("insert into publication_scope values(1)");
        db.update("insert into template(id,code,state,revision,remark) values(1,"
                + "'T1','DRAFT',2,'latest'),(2,'T2','DRAFT',1,'other')");
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "template-concurrency"),
                MesExecutionTemplateEntity.class);
        var access = mock(MesActualFactoryAccessService.class);
        when(access.require(anyString())).thenReturn("F1");
        var nodes = mock(MesIdentifierNodePolicyValidator.class);
        when(nodes.normalize(any(), any())).thenReturn(new MesIdentifierNodePolicyDTO());
        when(nodes.configurationJson(any())).thenReturn("{}");
        physical = mock(MesPhysicalTransformPolicyValidator.class);
        when(physical.normalize(any(), any())).thenReturn(MesPhysicalTransformPolicyValidator.NONE);
        var mapper = mock(MesExecutionTemplateMapper.class, call -> {
            if (call.getMethod().getName().equals("lockPublication")) {
                db.queryForObject("select id from publication_scope where id=1 for update", Long.class);
            }
            return 1;
        });
        var target = new MesExecutionTemplateServiceImpl(access, null, nodes, null, null, physical, null) {
            @Override
            public MesExecutionTemplateEntity getOne(Wrapper<MesExecutionTemplateEntity> wrapper) {
                wrapper.getSqlSegment();
                Long id = (Long) ((com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>) wrapper)
                        .getParamNameValuePairs().get("MPGENVAL1");
                return read(id, true);
            }

            @Override
            public MesExecutionTemplateEntity getById(Serializable id) {
                return read(((Number) id).longValue(), false);
            }

            @Override
            public List<MesExecutionTemplateEntity> list(Wrapper<MesExecutionTemplateEntity> wrapper) {
                String sql = wrapper.getSqlSegment();
                var rows = db.query(
                        "select * from template" + (sql.contains("status") ? " where state='PUBLISHED'" : ""),
                        (r, n) -> from(r));
                if (!sql.contains("status")) {
                    Object code = ((com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>) wrapper)
                            .getParamNameValuePairs().get("MPGENVAL3");
                    rows = rows.stream().filter(r -> r.getTemplateCode().equals(code)).toList();
                }
                if (concurrencyProbe) {
                    try {
                        conflictObservation.await(500, TimeUnit.MILLISECONDS);
                    } catch (Exception e) {
                        /* Serialized publisher times out alone, then the next sees committed state. */
                    }
                }
                return rows;
            }

            @Override
            public boolean saveOrUpdate(MesExecutionTemplateEntity t) {
                return write(t);
            }

            @Override
            public boolean updateById(MesExecutionTemplateEntity t) {
                return write(t);
            }

            private boolean write(MesExecutionTemplateEntity t) {
                int changed;
                if (t.getId() == null) {
                    t.setId(3L);
                    changed = db.update("insert into template(id,code,state,revision,remark,priority,"
                                    + "version_no) values(?,?,?,?,?,?,?)", t.getId(), t.getTemplateCode(), t.getStatus(),
                            t.getRevisionNo(), t.getRemark(), t.getPriority(), t.getVersionNo());
                } else {
                    changed = db.update(
                            "update template set state=?,revision=?,remark=?,priority=?,version_no=? where id=?",
                            t.getStatus(), t.getRevisionNo(), t.getRemark(), t.getPriority(), t.getVersionNo(),
                            t.getId());
                }
                if (pauseRetirement && "RETIRED".equals(t.getStatus())) {
                    retirementWritten.countDown();
                    try {
                        if (!continueSwitch.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("switch not released");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                }
                if (failAfterWrite || (writesUntilFailure > 0 && --writesUntilFailure == 0)) {
                    throw new IllegalStateException("persisted then failed");
                }
                if (writesUntilFalse > 0 && --writesUntilFalse == 0) {
                    return false;
                }
                return changed == 1;
            }
        };
        ReflectionTestUtils.setField(target, "baseMapper", mapper);
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        transactions = new DataSourceTransactionManager(ds);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        service = (MesExecutionTemplateServiceImpl) proxy.getProxy();
        scope();
    }

    @AfterEach
    void clear() {
        TenantContextHolder.clear();
    }

    @Test
    void staleDraftMustNotOverwriteNewerRevision() {
        var old = read(1, false);
        old.setRevisionNo(1L);
        old.setRemark("stale browser");

        assertThrows(RuntimeException.class, () -> service.saveDraft(old));

        assertEquals("latest", read(1, false).getRemark());
    }

    @Test
    void sameScopeConcurrentPublishMustNotBothPass() throws Exception {
        concurrencyProbe = true;
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);

        try {
            java.util.function.Function<Long, Callable<Boolean>> publish = id -> () -> {
                scope();
                try {
                    start.await();
                    service.publish(id, id == 1L ? 2L : 1L);
                    return true;
                } catch (RuntimeException e) {
                    return false;
                } finally {
                    clear();
                }
            };
            var first = pool.submit(publish.apply(1L));
            var second = pool.submit(publish.apply(2L));
            start.countDown();
            int successful = (first.get(10, TimeUnit.SECONDS) ? 1 : 0) + (second.get(10, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, successful);
            assertEquals(1, db.queryForObject("select count(*) from template where state='PUBLISHED'", Integer.class));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void freshSaveAdvancesOnceAndTheOtherBrowserStaysStale() {
        var browserA = read(1, false);
        var browserB = read(1, false);
        browserA.setRemark("A");

        assertEquals(3L, service.saveDraft(browserA).getRevisionNo());

        browserB.setRemark("B");

        assertThrows(RuntimeException.class, () -> service.saveDraft(browserB));

        assertEquals("A", read(1, false).getRemark());
    }

    @Test
    void missingRevisionNeverSilentlyAdoptsCurrentRecord() {
        var input = read(1, false);
        input.setRevisionNo(null);

        assertThrows(RuntimeException.class, () -> service.saveDraft(input));
        assertThrows(RuntimeException.class, () -> service.publish(1L, null));

        assertEquals(2L, read(1, false).getRevisionNo());
    }

    @Test
    void publishAndRetireRequireTheReviewedRevision() {
        assertThrows(RuntimeException.class, () -> service.publish(1L, 1L));

        assertEquals("DRAFT", read(1, false).getStatus());

        assertEquals(3L, service.publish(1L, 2L).getRevisionNo());
        assertThrows(RuntimeException.class, () -> service.retire(1L, 2L));

        assertEquals("PUBLISHED", read(1, false).getStatus());

        assertEquals(4L, service.retire(1L, 3L).getRevisionNo());

        assertEquals("RETIRED", read(1, false).getStatus());
    }

    @Test
    void legacyIdOnlyCommandsCannotBypassReview() {
        assertThrows(RuntimeException.class, () -> service.publish(1L));
        assertThrows(RuntimeException.class, () -> service.retire(1L));

        assertEquals("DRAFT", read(1, false).getStatus());
    }

    @Test
    void newDraftStartsAtOneWithoutTrustingClientCounter() {
        var fresh = read(1, false);
        fresh.setId(null);
        fresh.setTemplateCode("NEW").setRevisionNo(999L);

        assertEquals(1L, service.saveDraft(fresh).getRevisionNo());
    }

    @Test
    void copiedVersionUsesNewRevisionAndNextTemplateVersion() {
        var copy = service.copyVersion(1L);

        assertEquals(1L, copy.getRevisionNo());
        assertEquals(2, copy.getVersionNo());
    }

    @Test
    void writeFailureRollsBackStatusAndRevisionTogether() {
        failAfterWrite = true;

        assertThrows(RuntimeException.class, () -> service.publish(1L, 2L));

        assertEquals("DRAFT", read(1, false).getStatus());
        assertEquals(2L, read(1, false).getRevisionNo());
    }

    @Test
    void allowedDifferentPriorityTemplatesCanBothPublish() {
        db.update("update template set priority=1 where id=2");

        service.publish(1L, 2L);
        assertDoesNotThrow(() -> service.publish(2L, 1L));
    }

    @Test
    void oldRepeatableReadCallerCannotHideACommittedConflictingPublication() throws Exception {
        var outer = new org.springframework.transaction.support.TransactionTemplate(transactions);
        outer.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);

        outer.execute(ignored -> {
            assertEquals(0, db.queryForObject("select count(*) from template where state='PUBLISHED'", Integer.class));
            var executor = Executors.newSingleThreadExecutor();
            try {
                executor.submit(() -> {
                    scope();
                    try {
                        service.publish(1L, 2L);
                    } finally {
                        clear();
                    }
                }).get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                executor.shutdownNow();
            }
            assertThrows(RuntimeException.class, () -> service.publish(2L, 1L));
            return null;
        });

        assertEquals("DRAFT", read(2, false).getStatus());
    }

    @Test
    void revisionOverflowAndWrongTenantFailBeforeChanges() {
        db.update("update template set revision=? where id=1", Long.MAX_VALUE);

        assertThrows(RuntimeException.class, () -> service.publish(1L, Long.MAX_VALUE));

        TenantContextHolder.setTenantId(2L);

        assertThrows(RuntimeException.class, () -> service.retire(1L, Long.MAX_VALUE));

        assertEquals("DRAFT", read(1, false).getStatus());
    }

    @Test
    void legacyUpgradeBaselineZeroCanBeEditedToRevisionOne() {
        db.update("update template set revision=0 where id=1");

        assertEquals(1L, service.saveDraft(read(1, false)).getRevisionNo());
    }

    @Test
    void revisionIsExactTextEvenWithoutGlobalLongSerializer() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper()
                .valueToTree(read(1, false).setRevisionNo(9007199254740993L));

        assertTrue(json.path("revisionNo").isTextual());
        assertEquals("9007199254740993", json.path("revisionNo").textValue());
    }

    @Test
    void replacementRetiresOldAndPublishesNewInOneCommit() {
        replacementRows();
        replace(2L, 1L);

        assertEquals("RETIRED", read(1, false).getStatus());
        assertEquals(3L, read(1, false).getRevisionNo());
        assertEquals("latest", read(1, false).getRemark());
        assertEquals("PUBLISHED", read(2, false).getStatus());
        assertEquals(2L, read(2, false).getRevisionNo());
    }

    @Test
    void failedNewPublicationMustRestoreOldPublishedVersion() {
        replacementRows();
        writesUntilFailure = 2;

        assertThrows(IllegalStateException.class, () -> replace(2L, 1L));
        assertEquals("PUBLISHED", read(1, false).getStatus());
        assertEquals(2L, read(1, false).getRevisionNo());
        assertEquals("DRAFT", read(2, false).getStatus());
        assertEquals(1L, read(2, false).getRevisionNo());
    }

    @Test
    void falseNewPublicationWriteAlsoRestoresOldVersion() {
        replacementRows();
        writesUntilFalse = 2;

        assertThrows(RuntimeException.class, () -> replace(2L, 1L));
        assertReplacementUntouched();
    }

    @Test
    void replacementRequiresBothReviewedRevisions() {
        replacementRows();

        assertThrows(RuntimeException.class, () -> replace(1L, 1L));
        assertReplacementUntouched();
        assertThrows(RuntimeException.class, () -> replace(2L, 0L));
        assertReplacementUntouched();
        assertThrows(RuntimeException.class, () -> replace(null, 1L));
        assertReplacementUntouched();
    }

    @Test
    void replacementCannotChangeIdentityScopePriorityOrVersionDirection() {
        replacementRows();

        for (String column : List.of("material_code", "route_code", "workshop_code", "worksite_code")) {
            db.update("update template set " + column + "='OTHER' where id=2");
            assertThrows(RuntimeException.class, () -> replace(2L, 1L));
            assertReplacementUntouched();
            db.update("update template set " + column + "=null where id=2");
        }

        db.update("update template set priority=1 where id=2");

        assertThrows(RuntimeException.class, () -> replace(2L, 1L));

        db.update("update template set priority=0,code='OTHER' where id=2");

        assertThrows(RuntimeException.class, () -> replace(2L, 1L));

        db.update("update template set code='T1',version_no=0 where id=2");

        assertThrows(RuntimeException.class, () -> replace(2L, 1L));
        assertReplacementUntouched();
    }

    @Test
    void replacementRejectsFutureAndExpiredVersions() {
        replacementRows();

        for (int id : List.of(1, 2)) {
            db.update("update template set effective_from=? where id=?", java.time.LocalDateTime.now().plusDays(1), id);
            assertThrows(RuntimeException.class, () -> replace(2L, 1L));
            assertReplacementUntouched();
            db.update("update template set effective_from=null,effective_to=? where id=?",
                    java.time.LocalDateTime.now().minusDays(1), id);
            assertThrows(RuntimeException.class, () -> replace(2L, 1L));
            assertReplacementUntouched();
            db.update("update template set effective_to=null where id=?", id);
        }
    }

    @Test
    void otherPublishedConflictRollsTheWholeSwitchBack() {
        replacementRows();
        db.update("insert into template(id,code,state,revision,remark) values(3,"
                + "'CONFLICT','PUBLISHED',1,'existing')");

        assertThrows(RuntimeException.class, () -> replace(2L, 1L));
        assertReplacementUntouched();
        assertEquals("PUBLISHED", read(3, false).getStatus());
    }

    @Test
    void replacementPreviewIsReadOnlyAndRejectsAnObsoleteDraft() {
        replacementRows();

        var preview = service.replacementPreview(2L, 1L);

        assertEquals(1L, preview.getSource().getId());
        assertEquals(2L, preview.getDraft().getId());
        assertEquals("1", preview.getTenantId());
        assertEquals("F1", preview.getFactoryCode());
        assertReplacementUntouched();

        db.update("update template set revision=2 where id=2");

        assertThrows(RuntimeException.class, () -> service.replacementPreview(2L, 1L));
    }

    @Test
    void replacementPreviewNeverGuessesAmongDuplicateOldVersions() {
        replacementRows();
        db.update("insert into template(id,code,state,revision,version_no) values(3,'T1','PUBLISHED',1,3)");

        assertThrows(RuntimeException.class, () -> service.replacementPreview(2L, 1L));

        assertReplacementUntouched();
    }

    @Test
    void twoCompetingSwitchesCannotRetireOneSourceTwice() throws Exception {
        replacementRows();
        db.update("insert into template(id,code,state,revision,version_no) values(3,'T1','DRAFT',1,3)");
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);

        try {
            java.util.function.Function<Long, Callable<Boolean>> command = id -> () -> {
                scope();
                try {
                    start.await();
                    service.replacePublished(1L, 2L, id, 1L);
                    return true;
                } catch (RuntimeException e) {
                    return false;
                } finally {
                    clear();
                }
            };
            var a = executor.submit(command.apply(2L));
            var b = executor.submit(command.apply(3L));
            start.countDown();
            assertEquals(1, (a.get(10, TimeUnit.SECONDS) ? 1 : 0) + (b.get(10, TimeUnit.SECONDS) ? 1 : 0));
            assertEquals("RETIRED", read(1, false).getStatus());
            assertEquals(3L, read(1, false).getRevisionNo());
            assertEquals(1, db.queryForObject("select count(*) from template where state='PUBLISHED'", Integer.class));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void reversedIdsStillUseTheRequestedSourceAndDraft() {
        replacementRows();
        db.update("update template set code='TEMP' where id=1");
        db.update("update template set state='PUBLISHED',revision=4,version_no=1 where id=2");
        db.update("update template set code='T1',state='DRAFT',version_no=2 where id=1");

        service.replacePublished(2L, 4L, 1L, 2L);

        assertEquals("PUBLISHED", read(1, false).getStatus());
        assertEquals("RETIRED", read(2, false).getStatus());
    }

    @Test
    void dispatchReadersSeeOldPublicationUntilTheCompleteSwitchCommits() throws Exception {
        replacementRows();
        pauseRetirement = true;
        var executor = Executors.newSingleThreadExecutor();

        try {
            var future = executor.submit(() -> {
                scope();
                try {
                    replace(2L, 1L);
                } finally {
                    clear();
                }
            });
            assertTrue(retirementWritten.await(10, TimeUnit.SECONDS));
            assertEquals(List.of(1L), db.queryForList("select id from template where state='PUBLISHED'", Long.class));
            continueSwitch.countDown();
            future.get(10, TimeUnit.SECONDS);
            assertEquals(List.of(2L), db.queryForList("select id from template where state='PUBLISHED'", Long.class));
        } finally {
            continueSwitch.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void replacementCannotBypassPublicationReadiness() {
        replacementRows();

        doThrow(new com.kitex.common.core.exception.CheckedException("转换尚未就绪")).when(physical)
                .assertPublicationAvailable(any());
        assertThrows(RuntimeException.class, () -> replace(2L, 1L));
        assertReplacementUntouched();
    }

    @Test
    void replacementRejectsWrongTenantAndSelfReplacement() {
        replacementRows();

        assertThrows(RuntimeException.class, () -> service.replacePublished(1L, 2L, 1L, 2L));

        TenantContextHolder.setTenantId(2L);

        assertThrows(RuntimeException.class, () -> replace(2L, 1L));

        assertThrows(RuntimeException.class, () -> service.replacementPreview(2L, 1L));

        assertReplacementUntouched();
    }

    private void assertReplacementUntouched() {
        assertEquals("PUBLISHED", read(1, false).getStatus());
        assertEquals(2L, read(1, false).getRevisionNo());
        assertEquals("DRAFT", read(2, false).getStatus());
        assertEquals(1L, read(2, false).getRevisionNo());
    }

    private void replacementRows() {
        db.update("update template set state='PUBLISHED' where id=1");
        db.update("update template set code='T1',version_no=2 where id=2");
    }

    private void replace(Long sourceRevision, Long draftRevision) {
        service.replacePublished(1L, sourceRevision, 2L, draftRevision);
    }

    private MesExecutionTemplateEntity from(java.sql.ResultSet r) throws java.sql.SQLException {
        return row(r.getLong("id"), r.getString("code"), r.getString("state"), r.getLong("revision"),
                r.getString("remark"))
                .setPriority(r.getInt("priority")).setVersionNo(r.getInt("version_no"))
                .setMaterialCode(r.getString("material_code")).setRouteCode(r.getString("route_code"))
                .setProcessRouteCode(r.getString("process_route_code")).setRouteNodeCode(r.getString("route_node_code"))
                .setWorkshopCode(r.getString("workshop_code")).setWorksiteCode(r.getString("worksite_code"))
                .setEffectiveFrom(r.getObject("effective_from", java.time.LocalDateTime.class))
                .setEffectiveTo(r.getObject("effective_to", java.time.LocalDateTime.class));
    }

    private void scope() {
        TenantContextHolder.setTenantId(1L);
    }

    private MesExecutionTemplateEntity read(long id, boolean lock) {
        return db.queryForObject("select * from template where id=?" + (lock ? " for update" : ""), (r, n) -> from(r),
                id);
    }

    private MesExecutionTemplateEntity row(long id, String code, String state, long revision, String remark) {
        var r = new MesExecutionTemplateEntity().setFactoryCode("F1").setTemplateCode(code).setTemplateName(code)
                .setVersionNo(1).setReportMode("QUANTITY").setPriority(0).setStatus(state).setRevisionNo(revision)
                .setRemark(remark);
        r.setId(id);
        r.setTenantId(1L);
        return r;
    }
}
