package com.kitex.mom.wms.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.common.security.service.MomUser;
import com.kitex.mom.wms.dto.WmsInventoryRecountCommand;
import com.kitex.mom.wms.entity.WmsInBillEntity;
import com.kitex.mom.wms.entity.WmsInsideInventoryPlanDetailEntity;
import com.kitex.mom.wms.entity.WmsInsideInventoryPlanEntity;
import com.kitex.mom.wms.entity.WmsInventoryRecountRecordEntity;
import com.kitex.mom.wms.entity.WmsOutBillEntity;
import com.kitex.mom.wms.mapper.WmsInBillMapper;
import com.kitex.mom.wms.mapper.WmsInsideInventoryPlanDetailMapper;
import com.kitex.mom.wms.mapper.WmsInsideInventoryPlanMapper;
import com.kitex.mom.wms.mapper.WmsInventoryRecountRecordMapper;
import com.kitex.mom.wms.mapper.WmsOutBillMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WmsInventoryRecountServiceTest {
    private final WmsInsideInventoryPlanMapper plans = mock(WmsInsideInventoryPlanMapper.class);
    private final WmsInsideInventoryPlanDetailMapper details = mock(WmsInsideInventoryPlanDetailMapper.class);
    private final WmsInventoryRecountRecordMapper records = mock(WmsInventoryRecountRecordMapper.class);
    private final WmsInBillMapper inBills = mock(WmsInBillMapper.class);
    private final WmsOutBillMapper outBills = mock(WmsOutBillMapper.class);
    private final PermissionService permissions = mock(PermissionService.class);
    private final WmsInsideInventoryPlanEntity plan = new WmsInsideInventoryPlanEntity();
    private final WmsInsideInventoryPlanDetailEntity row = new WmsInsideInventoryPlanDetailEntity();

    @BeforeAll
    static void initializeMetadata() {
        var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "inventory-recount");
        for (var entity : List.of(WmsInsideInventoryPlanEntity.class, WmsInsideInventoryPlanDetailEntity.class,
                WmsInventoryRecountRecordEntity.class, WmsInBillEntity.class, WmsOutBillEntity.class)) {
            TableInfoHelper.initTableInfo(assistant, entity);
        }
    }

    @BeforeEach
    void setUp() {
        TenantContextHolder.setTenantId(1L);
        FactoryContextHolder.setFactoryCode("F1");
        var user = mock(MomUser.class);
        when(user.getUsername()).thenReturn("counter");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
        plan.setId(1L);
        plan.setTenantId(1L);
        plan.setFactoryCode("F1");
        plan.setWarehouseCode("W1");
        plan.setStatus("1");
        plan.setHandleState("0");
        row.setId(2L);
        row.setPlanId(1L);
        row.setTenantId(1L);
        row.setFactoryCode("F1");
        row.setWarehouseCode("W1");
        row.setStatus("1");
        row.setPlStatus("0");
        row.setInventoryNum(7d);
        when(plans.selectOne(any())).thenReturn(plan);
        when(details.selectOne(any())).thenReturn(row);
        when(inBills.selectCount(any())).thenReturn(0L);
        when(outBills.selectCount(any())).thenReturn(0L);
        when(details.updateById(any(WmsInsideInventoryPlanDetailEntity.class))).thenReturn(1);
        when(records.insert(any(WmsInventoryRecountRecordEntity.class))).thenReturn(1);
        when(permissions.hasPermission(anyString(), anyString())).thenReturn(true);
    }

    @AfterEach
    void clearScope() {
        TenantContextHolder.clear();
        FactoryContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void recountPreservesOldAndNewCountWithReasonAndOperator() {
        var result = service().recount(command());

        assertEquals(7d, result.getPreviousNum());
        assertEquals(8d, result.getRecountNum());
        assertEquals("重新核对", result.getReason());
        assertEquals("counter", result.getOperatorCode());
        assertEquals(8d, row.getInventoryNum());
        verify(records).insert(result);
        verify(details).updateById(row);
    }

    @Test
    void finalizedPlanAndUnprocessedDetailAreRequired() {
        plan.setStatus("0");
        assertThrows(CheckedException.class, () -> service().recount(command()));
        verify(records, never()).insert(any(WmsInventoryRecountRecordEntity.class));
    }

    @Test
    void processedDetailCannotBeRecounted() {
        row.setPlStatus("1");
        assertThrows(CheckedException.class, () -> service().recount(command()));
        verify(details, never()).updateById(any(WmsInsideInventoryPlanDetailEntity.class));
    }

    @Test
    void stalePageCountCannotOverwriteCurrentCount() {
        row.setInventoryNum(9d);
        assertThrows(CheckedException.class, () -> service().recount(command()));
        verify(records, never()).insert(any(WmsInventoryRecountRecordEntity.class));
    }

    @Test
    void existingAdjustmentBillBlocksRecountEvenWithoutProcessedMarker() {
        when(inBills.selectCount(any())).thenReturn(1L);
        assertThrows(CheckedException.class, () -> service().recount(command()));
        verify(details, never()).updateById(any(WmsInsideInventoryPlanDetailEntity.class));
    }

    @Test
    void anotherWarehousePermissionCannotRecountThisPlan() {
        when(permissions.hasPermission(anyString(), anyString())).thenReturn(false);

        assertThrows(CheckedException.class, () -> service().recount(command()));

        verify(records, never()).insert(any(WmsInventoryRecountRecordEntity.class));
    }

    @Test
    void retryOfSameRequestReturnsOriginalRecordAfterPlanIsHandled() {
        var original = new WmsInventoryRecountRecordEntity();
        original.setPlanId(1L);
        original.setPlanDetailId(2L);
        original.setRequestId(command().getRequestId());
        original.setPreviousNum(7d);
        original.setRecountNum(8d);
        original.setReason("重新核对");
        when(records.selectOne(any())).thenReturn(original);
        plan.setHandleState("1");

        assertSame(original, service().recount(command()));

        verify(records, never()).insert(any(WmsInventoryRecountRecordEntity.class));
        verify(details, never()).updateById(any(WmsInsideInventoryPlanDetailEntity.class));
    }

    @Test
    void reusedRequestIdWithDifferentQuantityIsRejected() {
        var original = new WmsInventoryRecountRecordEntity();
        original.setPlanDetailId(2L);
        original.setRequestId(command().getRequestId());
        original.setPreviousNum(7d);
        original.setRecountNum(9d);
        original.setReason("重新核对");
        when(records.selectOne(any())).thenReturn(original);

        assertThrows(CheckedException.class, () -> service().recount(command()));

        verify(details, never()).updateById(any(WmsInsideInventoryPlanDetailEntity.class));
    }

    @Test
    void historyIsScopedToCurrentPlanTenantAndFactory() {
        var original = new WmsInventoryRecountRecordEntity();
        when(records.selectList(any())).thenReturn(List.of(original));

        assertEquals(List.of(original), service().history(1L));

        @SuppressWarnings("unchecked")
        var query = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(records).selectList(query.capture());
        assertTrue(query.getValue().getSqlSegment().contains("plan_id"));
        assertTrue(query.getValue().getParamNameValuePairs().containsValue(1L));
        assertTrue(query.getValue().getParamNameValuePairs().containsValue("F1"));
    }

    @Test
    void failedCountWriteRollsBackInsertedRecountEvidence() {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        dataSource.setDriverClassName("org.h2.Driver");
        var db = new JdbcTemplate(dataSource);
        db.execute("CREATE TABLE recount_record (request_id varchar(64) primary key)");
        when(records.insert(any(WmsInventoryRecountRecordEntity.class))).thenAnswer(invocation -> {
            var record = (WmsInventoryRecountRecordEntity) invocation.getArgument(0);
            return db.update("INSERT INTO recount_record VALUES (?)", record.getRequestId());
        });
        when(details.updateById(any(WmsInsideInventoryPlanDetailEntity.class))).thenReturn(0);
        var proxy = new ProxyFactory(service());
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(dataSource),
                new AnnotationTransactionAttributeSource()));
        var transactional = (WmsInventoryRecountService) proxy.getProxy();

        assertThrows(CheckedException.class, () -> transactional.recount(command()));

        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM recount_record", Integer.class));
    }

    private WmsInventoryRecountService service() {
        return new WmsInventoryRecountService(new WmsInventoryPlanAccess(plans, details),
                details, records, inBills, outBills, permissions,
                mock(WmsInventoryAdjustmentApprovalService.class));
    }

    private WmsInventoryRecountCommand command() {
        var command = new WmsInventoryRecountCommand();
        command.setPlanDetailId(2L);
        command.setExpectedInventoryNum(7d);
        command.setRecountNum(8d);
        command.setReason(" 重新核对 ");
        command.setRequestId("e1bc22bb-7982-4320-b31c-c87c1ad475e7");
        return command;
    }
}
