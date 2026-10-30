package com.kitex.mom.wms.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.wms.entity.WmsReservationMovementPolicyEntity;
import com.kitex.mom.wms.mapper.WmsReservationMovementPolicyMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

class WmsReservationMovementPolicyServiceTest {
    private final WmsReservationMovementPolicyMapper mapper = mock(WmsReservationMovementPolicyMapper.class);
    private final WmsReservationMovementPolicyService service = new WmsReservationMovementPolicyService(mapper);

    @BeforeEach
    void setScope() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "movement-policy"),
                WmsReservationMovementPolicyEntity.class);
        TenantContextHolder.setTenantId(1L);
        FactoryContextHolder.setFactoryCode("F1");
    }

    @AfterEach
    void clearScope() {
        TenantContextHolder.clear();
        FactoryContextHolder.clear();
    }

    @Test
    void explicitlyDisabledProductionMovementCannotCreateReservation() {
        when(mapper.selectList(any())).thenReturn(List.of(policy("produce", false, true)));

        assertThrows(CheckedException.class,
                () -> service.ensureProductionReservable("PLAN_MRP_MAIN"));
        assertThrows(CheckedException.class,
                () -> service.ensureProductionReservable("PLAN_MRP_SUBSTITUTE"));
    }

    @Test
    void unknownProductionSourceIsRejectedEvenWithoutPolicy() {
        assertThrows(CheckedException.class,
                () -> service.ensureProductionReservable("PLAN_MRP_MAIN_X"));
    }

    @Test
    void unconfiguredOrInactivePolicyPreservesExistingReservationBehavior() {
        when(mapper.selectList(any())).thenReturn(List.of());
        assertDoesNotThrow(() -> service.ensureProductionReservable("PLAN_MRP_MAIN"));

        when(mapper.selectList(any())).thenReturn(List.of(policy("produce", false, false)));
        assertDoesNotThrow(() -> service.ensureProductionReservable("PLAN_MRP_MAIN"));
    }

    @Test
    void previewExplainsTheSameDefaultAndExplicitDenialAsReservationExecution() {
        when(mapper.selectList(any())).thenReturn(List.of());

        var defaultDecision = service.preview("produce");
        assertEquals(true, defaultDecision.allowed());
        assertEquals("DEFAULT_BEHAVIOR", defaultDecision.configurationSource());
        assertDoesNotThrow(() -> service.ensureProductionReservable("PLAN_MRP_MAIN"));

        WmsReservationMovementPolicyEntity blocked = policy("produce", false, true);
        blocked.setId(17L);
        when(mapper.selectList(any())).thenReturn(List.of(blocked));

        var configuredDecision = service.preview("produce");
        assertEquals(false, configuredDecision.allowed());
        assertEquals("FACTORY_POLICY", configuredDecision.configurationSource());
        assertEquals(17L, configuredDecision.policyId());
        assertThrows(CheckedException.class,
                () -> service.ensureProductionReservable("PLAN_MRP_MAIN"));
    }

    @Test
    void previewRejectsUnsupportedTypeAndConflictingActivePolicies() {
        assertThrows(CheckedException.class, () -> service.preview("purchase"));
        when(mapper.selectList(any())).thenReturn(List.of(
                policy("sales", true, true), policy("sales", false, true)));

        assertThrows(CheckedException.class, () -> service.preview("sales"));
        assertThrows(CheckedException.class, service::ensureSalesReservable);
    }

    @Test
    void salesAndProductionPoliciesDoNotAffectEachOther() {
        when(mapper.selectList(any())).thenReturn(List.of(policy("produce", false, true)));

        assertDoesNotThrow(service::ensureSalesReservable);
        assertThrows(CheckedException.class,
                () -> service.ensureProductionReservable("PLAN_MRP_MAIN"));
    }

    @Test
    void editingPolicyCannotSilentlyMoveItToAnotherBusinessType() {
        WmsReservationMovementPolicyEntity existing = policy("produce", true, true);
        existing.setId(9L);
        WmsReservationMovementPolicyEntity changed = policy("sales", false, true);
        changed.setId(9L);
        when(mapper.selectById(9L)).thenReturn(existing);
        when(mapper.selectCount(any())).thenReturn(0L);
        when(mapper.update(any(), any())).thenReturn(1);

        assertThrows(CheckedException.class, () -> service.savePolicy(changed));
    }

    @Test
    void missingMovementTypeReturnsValidationError() {
        WmsReservationMovementPolicyEntity policy = policy("produce", true, true);
        policy.setMovementType(null);

        assertThrows(CheckedException.class, () -> service.savePolicy(policy));
    }

    @Test
    void skippedTenantOrFactoryScopeCannotPreviewOrApplyReservationRules() {
        TenantContextHolder.setTenantSkip();
        assertThrows(CheckedException.class, () -> service.preview("sales"));
        assertThrows(CheckedException.class, service::ensureSalesReservable);
        TenantContextHolder.clear();
        TenantContextHolder.setTenantId(1L);
        FactoryContextHolder.setFactorySkip();
        assertThrows(CheckedException.class, () -> service.preview("produce"));
        verifyZeroInteractions(mapper);
    }

    private WmsReservationMovementPolicyEntity policy(String movementType, boolean reservable,
                                                       boolean status) {
        WmsReservationMovementPolicyEntity policy = new WmsReservationMovementPolicyEntity()
                .setMovementType(movementType)
                .setReservationAllowed(reservable)
                .setStatus(status)
                .setFactoryCode("F1");
        policy.setTenantId(1L);
        return policy;
    }
}
