package com.kitex.mom.mdm.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.kitex.admin.api.entity.SysMenuEntity;
import com.kitex.admin.api.feign.RemoteDictService;
import com.kitex.admin.api.feign.RemoteMenuService;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mdm.controller.MdmWarehouseController;
import com.kitex.mom.mdm.entity.MdmWarehouseEntity;
import com.kitex.mom.mdm.mapper.MdmWarehouseMapper;
import com.kitex.mom.mdm.service.impl.MdmWarehouseServiceImpl;
import com.kitex.mom.mes.api.feign.RemoteMesWarehouseLifecycleService;
import com.kitex.mom.plan.feign.RemotePlnWarehouseLifecycleService;
import com.kitex.mom.wms.api.feign.RemoteWmsWarehouseLifecycleService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 实际控制器、MyBatis仓库查询/删除与Spring事务；远程服务在后续用例中单独替代。
 */
class MdmWarehouseDeletionTest {

    private JdbcTemplate db;
    private MdmWarehouseController controller;
    private RemoteMenuService menus;
    private RemoteWmsWarehouseLifecycleService wms;
    private RemoteMesWarehouseLifecycleService mes;
    private RemotePlnWarehouseLifecycleService plan;

    @BeforeEach
    void setup() throws Exception {
        FactoryContextHolder.setFactoryCode("F1");
        TenantContextHolder.setTenantId(1L);
        var source = new DriverManagerDataSource("jdbc:h2:mem:warehouse-delete-" + UUID.randomUUID()
                + ";DB_CLOSE_DELAY=-1", "sa", "");
        source.setDriverClassName("org.h2.Driver");
        db = new JdbcTemplate(source);
        var configuration = new MybatisConfiguration();
        configuration.addMapper(MdmWarehouseMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        var sessions = new SqlSessionTemplate(factory.getObject());
        var table = TableInfoHelper.getTableInfo(MdmWarehouseEntity.class);
        var ddl = new StringBuilder("create table mdm_warehouse(id bigint primary key");
        for (var field : table.getFieldList()) {
            String type = field.getPropertyType() == Boolean.class ? "boolean"
                    : field.getPropertyType() == Long.class ? "bigint" : "varchar";
            ddl.append(',').append(field.getColumn()).append(' ').append(type);
        }
        db.execute(ddl.append(')').toString());
        db.execute("create table mdm_warehouse_location(id bigint primary key,tenant_id bigint,"
                + "factory_code varchar,warehouse_code varchar,del_flag varchar)");
        db.execute("create table mdm_bom_child(id bigint primary key,tenant_id bigint,"
                + "factory_code varchar,default_issuance_warehouse varchar,del_flag varchar)");
        menus = mock(RemoteMenuService.class);
        wms = mock(RemoteWmsWarehouseLifecycleService.class);
        mes = mock(RemoteMesWarehouseLifecycleService.class);
        plan = mock(RemotePlnWarehouseLifecycleService.class);
        when(menus.getMenuDetails(any())).thenReturn(R.ok(List.of()));
        when(wms.references(anyString())).thenReturn(R.ok(List.of()));
        when(wms.businessReferences(anyString())).thenReturn(R.ok(List.of()));
        when(wms.cleanup(anyString())).thenReturn(R.ok(true));
        when(mes.references(anyString(), anyLong())).thenReturn(R.ok(List.of()));
        when(plan.references(anyString())).thenReturn(R.ok(List.of()));
        var target = new MdmWarehouseServiceImpl(mock(RemoteDictService.class),
                mock(MdmFactoryModelService.class), mock(MdmWarehouseLocationService.class), menus, wms, mes, plan);
        ReflectionTestUtils.setField(target, "baseMapper", sessions.getMapper(MdmWarehouseMapper.class));
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),
                new AnnotationTransactionAttributeSource()));
        controller = new MdmWarehouseController((MdmWarehouseService) proxy.getProxy());
        warehouse(1, "F1", 1, false);
    }

    @AfterEach
    void clearContext() {
        FactoryContextHolder.clear();
        TenantContextHolder.clear();
    }

    @Test
    void menuRegistryIncludesStoppedAndEnabledActualWarehouseStatesOnlyInCurrentScope() {
        warehouse(2, "F1", 1, true);
        warehouse(3, "F2", 1, true);
        warehouse(4, "F1", 2, true);
        warehouse(5, "F1", 1, true);
        db.update("update mdm_warehouse set del_flag='1' where id=5");

        assertEquals(java.util.Map.of("W1", false, "W2", true), controller.menuStates().getData());
        assertEquals(List.of("W1", "W2"), controller.getMenuConfigurationWarehouses().getData()
                .stream().map(MdmWarehouseEntity::getWarehouseCode).toList());
    }

    @Test
    void menuRegistryRejectsMissingStatusDuplicateCodesAndSkippedScopes() {
        db.update("update mdm_warehouse set status=null where id=1");

        assertThrows(CheckedException.class, () -> controller.menuStates());

        db.update("update mdm_warehouse set status=false where id=1");
        warehouse(2, "F1", 1, true);
        db.update("update mdm_warehouse set warehouse_code='W1' where id=2");

        assertThrows(CheckedException.class, () -> controller.menuStates());

        db.update("update mdm_warehouse set warehouse_code='W2' where id=2");
        FactoryContextHolder.setFactorySkip();

        assertThrows(CheckedException.class, () -> controller.menuStates());

        FactoryContextHolder.clear();
        FactoryContextHolder.setFactoryCode("F1");
        TenantContextHolder.clear();

        assertThrows(CheckedException.class, () -> controller.menuStates());
    }

    @Test
    void enabledWarehouseCannotBeDeleted() {
        db.update("update mdm_warehouse set status=true where id=1");

        assertThrows(CheckedException.class, () -> controller.removeById(new Long[]{1L}));

        assertLive(1);
    }

    @Test
    void anotherFactoryWarehouseCannotBeDeleted() {
        warehouse(2, "F2", 1, false);

        assertThrows(CheckedException.class, () -> controller.removeById(new Long[]{2L}));

        assertLive(2);
    }

    @Test
    void anotherTenantWarehouseCannotBeDeleted() {
        warehouse(2, "F1", 2, false);

        assertThrows(CheckedException.class, () -> controller.removeById(new Long[]{2L}));

        assertLive(2);
    }

    @Test
    void locationHistoryPreventsDeletion() {
        db.update("insert into mdm_warehouse_location values(10,1,'F1','W1','1')");

        assertThrows(CheckedException.class, () -> controller.removeById(new Long[]{1L}));

        assertLive(1);
    }

    @Test
    void invalidLaterWarehouseLeavesWholeBatchUnchanged() {
        warehouse(2, "F1", 1, true);

        assertThrows(CheckedException.class, () -> controller.removeById(new Long[]{1L, 2L}));

        assertLive(1);
        assertLive(2);

        verify(wms, never()).cleanup(anyString());
    }

    @Test
    void nonexistentIdRejectsWholeBatch() {
        assertThrows(CheckedException.class, () -> controller.removeById(new Long[]{1L, 99L}));

        assertLive(1);
    }

    @Test
    void emptyBatchIsRejected() {
        assertThrows(CheckedException.class, () -> controller.removeById(new Long[]{}));
    }

    @Test
    void duplicateIdsAreRejected() {
        assertThrows(CheckedException.class, () -> controller.removeById(new Long[]{1L, 1L}));

        assertLive(1);
    }

    @Test
    void unusedDisabledWarehouseIsDeleted() {
        assertTrue(controller.removeById(new Long[]{1L}).getData());

        assertEquals("1", db.queryForObject("select del_flag from mdm_warehouse where id=1", String.class));
    }

    @Test
    void sameCodeInOtherScopeDoesNotBlockDeletion() {
        db.update("insert into mdm_warehouse_location values(10,2,'F1','W1','0'),(11,1,'F2','W1','0')");

        assertTrue(controller.removeById(new Long[]{1L}).getData());
    }

    @Test
    void bomWarehouseIdReferencePreventsDeletion() {
        db.update("insert into mdm_bom_child values(10,1,'F1','1','1')");

        var error = assertThrows(CheckedException.class, () -> controller.removeById(new Long[]{1L}));

        assertTrue(error.getMessage().contains("BOM"));
        assertLive(1);
    }

    @Test
    void wmsBusinessHistoryPreventsDeletion() {
        when(wms.businessReferences("W1")).thenReturn(R.ok(List.of("WMS实时库存及其历史")));

        var error = assertThrows(CheckedException.class, () -> controller.removeById(new Long[]{1L}));

        assertTrue(error.getMessage().contains("WMS实时库存"));
        assertLive(1);
    }

    @Test
    void mesMaterialHistoryPreventsDeletion() {
        when(mes.references("W1", 1L)).thenReturn(R.ok(List.of("MES投料及其历史")));

        var error = assertThrows(CheckedException.class, () -> controller.removeById(new Long[]{1L}));

        assertTrue(error.getMessage().contains("MES投料"));
        assertLive(1);
    }

    @Test
    void planOnlyDemandPreventsDeletionBeforeAnyWmsOrMesRecordExists() {
        when(plan.references("W1")).thenReturn(R.ok(List.of("PLAN产品需求")));

        var error = assertThrows(CheckedException.class, () -> controller.removeById(new Long[]{1L}));

        assertTrue(error.getMessage().contains("PLAN产品需求"));
        assertLive(1);
    }

    @Test
    void failedPlanLookupCannotMeanUnusedWarehouse() {
        when(plan.references("W1")).thenReturn(R.failed("offline"));

        assertThrows(CheckedException.class, () -> controller.removeById(new Long[]{1L}));

        assertLive(1);
    }

    @Test
    void unknownPlanLookupCannotMeanUnusedWarehouse() {
        when(plan.references("W1")).thenReturn(R.ok());

        assertThrows(CheckedException.class, () -> controller.removeById(new Long[]{1L}));

        assertLive(1);
    }

    @Test
    void throwingPlanLookupCannotMeanUnusedWarehouse() {
        when(plan.references("W1")).thenThrow(new IllegalStateException("timeout"));

        assertThrows(CheckedException.class, () -> controller.removeById(new Long[]{1L}));

        assertLive(1);
    }

    @Test
    void synchronizedMenuIsAutomaticallyCleanedWhenUnusedDisabledWarehouseIsDeleted() {
        var menu = new SysMenuEntity();
        menu.setPath("/wms/warehouseManege/F1/W1");
        when(menus.getMenuDetails(any())).thenReturn(R.ok(List.of(menu)));

        assertTrue(controller.removeById(new Long[]{1L}).getData());

        assertEquals("1", db.queryForObject("select del_flag from mdm_warehouse where id=1", String.class));
    }

    @Test
    void remoteFailureOrUnknownResultNeverMeansUnused() {
        for (R<List<String>> response : List.of(R.<List<String>>failed("offline"), R.<List<String>>ok())) {
            when(wms.businessReferences("W1")).thenReturn(response);

            assertThrows(CheckedException.class, () -> controller.removeById(new Long[]{1L}));

            assertLive(1);
        }

        when(wms.businessReferences("W1")).thenThrow(new IllegalStateException("timeout"));

        assertThrows(CheckedException.class, () -> controller.removeById(new Long[]{1L}));

        assertLive(1);
    }

    @Test
    void failedCleanupKeepsDisabledWarehouseAvailableForRetry() {
        when(wms.cleanup("W1")).thenReturn(R.failed("offline"));

        assertThrows(CheckedException.class, () -> controller.removeById(new Long[]{1L}));

        assertLive(1);

        when(wms.cleanup("W1")).thenReturn(R.ok(true));

        assertTrue(controller.removeById(new Long[]{1L}).getData());
    }

    @Test
    void unknownFalseOrThrowingCleanupCannotDeleteMaster() {
        for (R<Boolean> response : List.of(R.<Boolean>ok(), R.ok(false), R.<Boolean>failed("offline"))) {
            when(wms.cleanup("W1")).thenReturn(response);

            assertThrows(CheckedException.class, () -> controller.removeById(new Long[]{1L}));

            assertLive(1);
        }

        when(wms.cleanup("W1")).thenReturn(null);

        assertThrows(CheckedException.class, () -> controller.removeById(new Long[]{1L}));

        when(wms.cleanup("W1")).thenThrow(new IllegalStateException("timeout"));

        assertThrows(CheckedException.class, () -> controller.removeById(new Long[]{1L}));
        assertLive(1);
    }

    @Test
    void laterCleanupFailureRetainsWholeDisabledBatchAndCanRetry() {
        warehouse(2, "F1", 1, false);
        when(wms.cleanup("W2")).thenReturn(R.failed("offline"));

        assertThrows(CheckedException.class, () -> controller.removeById(new Long[]{1L, 2L}));

        assertLive(1);
        assertLive(2);

        when(wms.cleanup("W2")).thenReturn(R.ok(true));

        assertTrue(controller.removeById(new Long[]{1L, 2L}).getData());
    }

    @Test
    void failedMesLookupNeverMeansUnused() {
        when(mes.references("W1", 1L)).thenReturn(null);

        assertThrows(CheckedException.class, () -> controller.removeById(new Long[]{1L}));

        assertLive(1);
    }

    @Test
    void missingOrSkippedContextCannotDelete() {
        FactoryContextHolder.clear();

        assertThrows(CheckedException.class, () -> controller.removeById(new Long[]{1L}));

        FactoryContextHolder.setFactoryCode("F1");
        FactoryContextHolder.setFactorySkip();

        assertThrows(CheckedException.class, () -> controller.removeById(new Long[]{1L}));

        FactoryContextHolder.clear();
        FactoryContextHolder.setFactoryCode("F1");
        TenantContextHolder.clear();

        assertThrows(CheckedException.class, () -> controller.removeById(new Long[]{1L}));

        assertLive(1);
    }

    @Test
    void secondLocalDeleteFailureRollsBackFirstWarehouse() {
        warehouse(2, "F1", 1, false);
        db.execute("alter table mdm_warehouse add constraint fail_second_delete "
                + "check (id != 2 OR del_flag = '0')");

        assertThrows(RuntimeException.class, () -> controller.removeById(new Long[]{1L, 2L}));

        assertLive(1);
        assertLive(2);
    }

    @Test
    void warehouseCodeCannotBeRewrittenAfterCreation() {
        var update = updateRequest(1L).setWarehouseCode("RENAMED");

        assertThrows(CheckedException.class, () -> controller.updateById(update));

        assertEquals("W1", db.queryForObject("select warehouse_code from mdm_warehouse where id=1", String.class));
    }

    @Test
    void warehouseCannotBeMovedToAnotherFactoryByUpdate() {
        var update = updateRequest(1L).setFactoryCode("F2");

        assertThrows(CheckedException.class, () -> controller.updateById(update));

        assertEquals("F1", db.queryForObject("select factory_code from mdm_warehouse where id=1", String.class));
    }

    @Test
    void warehouseCannotBeMovedToAnotherTenantByUpdate() {
        var update = updateRequest(1L);
        update.setTenantId(2L);

        assertThrows(CheckedException.class, () -> controller.updateById(update));

        assertEquals(1L, db.queryForObject("select tenant_id from mdm_warehouse where id=1", Long.class));
    }

    @Test
    void updateCannotEditWarehouseOutsideCurrentScope() {
        warehouse(2, "F2", 1, false);

        assertThrows(CheckedException.class, () -> controller.updateById(updateRequest(2L)));

        assertNull(db.queryForObject("select warehouse_name from mdm_warehouse where id=2", String.class));
    }

    @Test
    void configurationCannotEditWarehouseOutsideCurrentScope() {
        warehouse(2, "F2", 1, false);
        var update = new MdmWarehouseEntity().setWarehouseCode("W2").setIsBatch(1);

        assertThrows(CheckedException.class, () -> controller.updateConfig(update));

        assertNull(db.queryForObject("select is_batch from mdm_warehouse where id=2", Integer.class));
    }

    @Test
    void configurationRequiresAnExistingOwnedWarehouse() {
        var update = new MdmWarehouseEntity().setWarehouseCode("MISSING").setIsBatch(1);

        assertThrows(CheckedException.class, () -> controller.updateConfig(update));
    }

    @Test
    void partialBatchConfigurationPreservesExistingContainerSetting() {
        db.update("update mdm_warehouse set is_batch=0,is_container=1 where id=1");
        var update = new MdmWarehouseEntity().setWarehouseCode("W1").setIsBatch(1);

        assertTrue(controller.updateConfig(update).getData());

        assertEquals(1, db.queryForObject("select is_batch from mdm_warehouse where id=1", Integer.class));
        assertEquals(1, db.queryForObject("select is_container from mdm_warehouse where id=1", Integer.class));
    }

    @Test
    void partialContainerConfigurationPreservesExistingBatchSetting() {
        db.update("update mdm_warehouse set is_batch=1,is_container=0 where id=1");
        var update = new MdmWarehouseEntity().setWarehouseCode("W1").setIsContainer(1);

        assertTrue(controller.updateConfig(update).getData());

        assertEquals(1, db.queryForObject("select is_batch from mdm_warehouse where id=1", Integer.class));
        assertEquals(1, db.queryForObject("select is_container from mdm_warehouse where id=1", Integer.class));
    }

    @Test
    void configurationRequiresAtLeastOneFlag() {
        var update = new MdmWarehouseEntity().setWarehouseCode("W1");

        assertThrows(CheckedException.class, () -> controller.updateConfig(update));
    }

    @Test
    void configurationRejectsFlagValuesOutsideZeroAndOne() {
        for (int invalid : List.of(-1, 2)) {
            var batch = new MdmWarehouseEntity().setWarehouseCode("W1").setIsBatch(invalid);
            var container = new MdmWarehouseEntity().setWarehouseCode("W1").setIsContainer(invalid);

            assertThrows(CheckedException.class, () -> controller.updateConfig(batch));
            assertThrows(CheckedException.class, () -> controller.updateConfig(container));
        }
    }

    @Test
    void ordinaryUpdateCannotWriteInvalidWarehouseFlags() {
        db.update("update mdm_warehouse set warehouse_name='旧名称' where id=1");

        for (int invalid : List.of(-1, 2)) {
            var batch = updateRequest(1L).setIsBatch(invalid);
            var container = updateRequest(1L).setIsContainer(invalid);
            assertThrows(CheckedException.class, () -> controller.updateById(batch));
            assertThrows(CheckedException.class, () -> controller.updateById(container));
        }
        assertEquals("旧名称", db.queryForObject("select warehouse_name from mdm_warehouse where id=1", String.class));
    }

    @Test
    void configurationPermissionsAuthorizeOnlyTheRequestedFlags() throws Exception {
        var batch = new MdmWarehouseEntity().setWarehouseCode("W1").setIsBatch(1);
        var container = new MdmWarehouseEntity().setWarehouseCode("W1").setIsContainer(1);
        var both = new MdmWarehouseEntity().setWarehouseCode("W1").setIsBatch(1).setIsContainer(1);

        assertTrue(configAllowed(Set.of("wms_wmsWarehouseMenu_batch"), batch));
        assertFalse(configAllowed(Set.of("wms_wmsWarehouseMenu_batch"), container));
        assertFalse(configAllowed(Set.of("wms_wmsWarehouseMenu_batch"), both));
        assertTrue(configAllowed(Set.of("wms_wmsWarehouseMenu_container"), container));
        assertFalse(configAllowed(Set.of("wms_wmsWarehouseMenu_container"), batch));
        assertTrue(configAllowed(Set.of("wms_wmsWarehouseMenu_batch", "wms_wmsWarehouseMenu_container"), both));
        assertTrue(configAllowed(Set.of("mdm_mdmWarehouse_edit"), both));
        assertFalse(configAllowed(Set.of(), batch));
    }

    private boolean configAllowed(Set<String> permissions, MdmWarehouseEntity request) throws Exception {
        String expression = MdmWarehouseController.class.getMethod("updateConfig", MdmWarehouseEntity.class)
                .getAnnotation(PreAuthorize.class).value();
        var context = new StandardEvaluationContext();
        context.setVariable("p0", request);
        context.setBeanResolver((evaluationContext, bean) -> new PermissionGate(permissions));
        return Boolean.TRUE
                .equals(new SpelExpressionParser().parseExpression(expression).getValue(context, Boolean.class));
    }

    @Test
    void disableAndReenablePreserveMasterIdentityAndConfiguration() {
        db.update("update mdm_warehouse set status=true,is_batch=1,is_container=1 where id=1");

        assertTrue(controller.updateById(updateRequest(1L).setStatus(false)).getData());
        assertTrue(controller.updateById(updateRequest(1L).setStatus(true)).getData());

        assertEquals(1, db.queryForObject("select is_batch from mdm_warehouse where id=1", Integer.class));
        assertEquals(1, db.queryForObject("select is_container from mdm_warehouse where id=1", Integer.class));
        assertLive(1);

        verify(wms, never()).cleanup(anyString());
    }

    @Test
    void ordinaryUpdateMayOmitStableScopeFields() {
        assertTrue(controller.updateById(updateRequest(1L)).getData());

        assertEquals("新名称", db.queryForObject("select warehouse_name from mdm_warehouse where id=1", String.class));
        assertEquals("W1", db.queryForObject("select warehouse_code from mdm_warehouse where id=1", String.class));
        assertEquals("F1", db.queryForObject("select factory_code from mdm_warehouse where id=1", String.class));
    }

    @Test
    void ordinaryUpdateCannotBypassDeletionGuardThroughDeleteFlag() {
        var update = updateRequest(1L);
        update.setDelFlag("1");

        assertThrows(CheckedException.class, () -> controller.updateById(update));

        assertLive(1);
    }

    private MdmWarehouseEntity updateRequest(Long id) {
        var update = new MdmWarehouseEntity().setWarehouseName("新名称");
        update.setId(id);
        return update;
    }

    private void warehouse(long id, String factory, long tenant, boolean enabled) {
        db.update("insert into mdm_warehouse(id,tenant_id,factory_code,warehouse_code,status,del_flag)"
                + " values(?,?,?,?,?,'0')", id, tenant, factory, "W" + id, enabled);
    }

    private void assertLive(long id) {
        assertEquals("0", db.queryForObject("select del_flag from mdm_warehouse where id=?", String.class, id));
    }

    public record PermissionGate(Set<String> permissions) {
        public boolean hasPermission(String permission) {
            return permissions.contains(permission);
        }
    }
}
