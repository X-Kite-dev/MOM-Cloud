package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.mom.mdm.feign.RemoteMdmFactoryAccessService;
import com.kitex.mom.mes.entity.MesMaterialReturnOperationEntity;
import com.kitex.mom.mes.entity.MesWarehouseStockFeedJudgeEntity;
import com.kitex.mom.mes.mapper.MesMaterialReturnOperationMapper;
import com.kitex.mom.mes.mapper.MesWarehouseStockFeedJudgeMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MesMaterialReturnActionReadTest {
    private MesWarehouseStockFeedJudgeServiceImpl service;
    private MesWarehouseStockFeedJudgeMapper requests;
    private MesMaterialReturnOperationMapper operations;
    private static final String ORIGINAL = "RETURN-ACTION-ORIGINAL-001";

    @BeforeEach void setup() throws Exception {
        TenantContextHolder.setTenantId(1L);
        FactoryContextHolder.setFactoryCode("F1");
        var user = new MomUser(7L, "operator", 1L, null, null, null, "Operator", null,
                1L, "unused", true, true, "USER", true, true, List.of());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, "unused", List.of()));
        var factories = mock(RemoteMdmFactoryAccessService.class);
        when(factories.check(any())).thenReturn(R.ok(true));
        operations = mock(MesMaterialReturnOperationMapper.class);
        var dependencies = new HashMap<Class<?>, Object>();
        dependencies.put(MesActualFactoryAccessService.class, new MesActualFactoryAccessService(factories));
        dependencies.put(MesMaterialReturnOperationMapper.class, operations);
        var constructor = MesWarehouseStockFeedJudgeServiceImpl.class.getConstructors()[0];
        service = (MesWarehouseStockFeedJudgeServiceImpl) constructor.newInstance(Arrays.stream(constructor.getParameterTypes())
                .map(type -> dependencies.computeIfAbsent(type, ignored -> mock(type))).toArray());
        requests = mock(MesWarehouseStockFeedJudgeMapper.class);
        ReflectionTestUtils.setField(service, "baseMapper", requests);
        for (var type : List.of(MesWarehouseStockFeedJudgeEntity.class, MesMaterialReturnOperationEntity.class)) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "return-action"), type);
        }
    }
    @AfterEach void clear() {
        TenantContextHolder.clear(); FactoryContextHolder.clear(); SecurityContextHolder.clearContext();
    }
    private MesMaterialReturnOperationEntity operation(String type) {
        var row = new MesMaterialReturnOperationEntity().setRequestId(ORIGINAL).setReturnRequestId(101L)
                .setFactoryCode("F1").setOperatorId(7L).setOperatorCode("operator").setBeforeStatus("SUBMITTED")
                .setOperationType(type).setAfterStatus(type.equals("WITHDRAW") ? "WITHDRAWN" : "REJECTED")
                .setRequestFingerprint("a".repeat(64));
        row.setId(201L); row.setTenantId(1L); return row;
    }
    private MesWarehouseStockFeedJudgeEntity request() {
        var row = new MesWarehouseStockFeedJudgeEntity().setFactoryCode("F1").setWorkflowVersion("V2")
                .setApplicantId(8L).setApplicantCode("applicant").setReviewRequestId(ORIGINAL)
                .setReviewFingerprint("a".repeat(64)).setReviewerId(7L).setJudgePersonCode("operator").setStatus("REJECTED");
        row.setId(101L); row.setTenantId(1L); return row;
    }
    @Test void contextsSeparateReviewAndWithdrawalWithoutInventoryReads() {
        var review = service.reviewContext(); var withdrawal = service.withdrawContext();
        assertNotNull(review); assertNotNull(withdrawal); assertEquals("REVIEW", review.kind());
        assertEquals("WITHDRAW", withdrawal.kind()); assertEquals("1", review.tenantId());
        assertEquals("7", review.actorUserId()); assertEquals("operator", review.actorCode());
        verifyNoMoreInteractions(requests, operations);
    }
    @Test void originalLookupUsesExactTenantFactoryActorKindAndRequest() {
        when(operations.selectOne(any(Wrapper.class))).thenAnswer(call -> {
            String sql = ((Wrapper<?>) call.getArgument(0)).getSqlSegment();
            for (String field : List.of("tenant_id =", "factory_code =", "operator_id =", "operator_code =", "request_id =", "operation_type IN")) assertTrue(sql.contains(field), sql);
            assertFalse(sql.contains("LIKE")); return operation("REJECT");
        });
        when(requests.selectOne(any(Wrapper.class))).thenReturn(request());
        var receipt = service.reviewReceipt(ORIGINAL);
        assertNotNull(receipt); assertEquals(201L, receipt.operation().getId()); assertEquals(101L, receipt.request().getId());
        verify(operations).selectOne(any(Wrapper.class)); verify(requests).selectOne(any(Wrapper.class));
        verifyNoMoreInteractions(operations, requests);
    }
    @Test void absentOperationDoesNotLoadARequestOrMutateAnything() {
        var receipt = service.reviewReceipt(ORIGINAL);
        assertNotNull(receipt); assertNull(receipt.operation()); assertNull(receipt.request());
        verify(operations).selectOne(any(Wrapper.class)); verifyNoMoreInteractions(operations, requests);
    }
    @Test void foreignOrWrongKindOperationsCannotBeReturned() {
        for (String field : List.of("tenant", "factory", "actor", "code", "request", "kind")) {
            var row = operation("REJECT");
            switch (field) {
                case "tenant" -> row.setTenantId(2L); case "factory" -> row.setFactoryCode("F2");
                case "actor" -> row.setOperatorId(8L); case "code" -> row.setOperatorCode("other");
                case "request" -> row.setRequestId("RETURN-OTHER-ORIGINAL-001"); case "kind" -> row.setOperationType("WITHDRAW");
            }
            when(operations.selectOne(any(Wrapper.class))).thenReturn(row);
            assertThrows(RuntimeException.class, () -> service.reviewReceipt(ORIGINAL), field);
        }
    }
    @Test void originalRequestMustMatchTenantFactoryVersionAndReviewBinding() {
        when(operations.selectOne(any(Wrapper.class))).thenReturn(operation("REJECT"));
        for (String field : List.of("tenant", "factory", "id", "version", "review", "fingerprint", "actor")) {
            var row = request();
            switch (field) {
                case "tenant" -> row.setTenantId(2L); case "factory" -> row.setFactoryCode("F2");
                case "id" -> row.setId(102L); case "version" -> row.setWorkflowVersion("LEGACY");
                case "review" -> row.setReviewRequestId("RETURN-OTHER-ORIGINAL-001");
                case "fingerprint" -> row.setReviewFingerprint("b".repeat(64)); case "actor" -> row.setReviewerId(8L);
            }
            when(requests.selectOne(any(Wrapper.class))).thenReturn(row);
            assertThrows(RuntimeException.class, () -> service.reviewReceipt(ORIGINAL), field);
        }
    }
    @Test void withdrawalRequiresItsOriginalApplicantAndTerminalState() {
        when(operations.selectOne(any(Wrapper.class))).thenReturn(operation("WITHDRAW"));
        var row = request().setApplicantId(7L).setApplicantCode("operator").setStatus("WITHDRAWN");
        when(requests.selectOne(any(Wrapper.class))).thenReturn(row);
        assertNotNull(service.withdrawReceipt(ORIGINAL));
        row.setApplicantId(8L); assertThrows(RuntimeException.class, () -> service.withdrawReceipt(ORIGINAL));
    }
    @Test void invalidIdsOrMismatchedTenantCannotReachAnyLookup() {
        for (String id : List.of("short", " " + ORIGINAL, ORIGINAL + "\n", "x".repeat(101))) assertThrows(RuntimeException.class, () -> service.reviewReceipt(id));
        TenantContextHolder.setTenantId(2L); assertThrows(RuntimeException.class, () -> service.withdrawContext());
        verifyNoMoreInteractions(operations, requests);
    }
    @Test void reviewAndWithdrawalCapabilitiesKeepTheirSeparateExistingPermissions() throws Exception {
        for (Class<?> type : List.of(MesWarehouseStockFeedJudgeServiceImpl.class,
                com.kitex.mom.mes.controller.MesWarehouseStockFeedJudgeController.class)) {
            for (String name : List.of("reviewContext", "reviewReceipt", "withdrawContext", "withdrawReceipt")) {
                var method = name.endsWith("Context") ? type.getMethod(name) : type.getMethod(name, String.class);
                String expected = name.startsWith("review") ? "stockFeedJudge:judge" : "stockFeedJudge:submit";
                assertEquals("@pms.hasPermission('" + expected + "')",
                        method.getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class).value());
            }
        }
    }
    @Test void listQueryExcludesLargeEvidenceSnapshotAndBoundsPageSize() {
        when(requests.selectPage(any(), any(Wrapper.class))).thenAnswer(call -> {
            com.baomidou.mybatisplus.extension.plugins.pagination.Page<?> page = call.getArgument(0);
            Wrapper<?> query = call.getArgument(1);
            assertEquals(100, page.getSize());
            assertFalse(query.getSqlSelect().contains("evidence_snapshot"));
            assertTrue(query.getSqlSelect().contains("num"));
            assertTrue(query.getSqlSelect().contains("wms_actual_received_num"));
            return page;
        });
        service.pageRequests(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 5000), new MesWarehouseStockFeedJudgeEntity());
    }
}
