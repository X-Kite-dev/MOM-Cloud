package com.kitex.mom.mes.service.impl;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.mom.mdm.dto.MdmFactoryAccessCheck;
import com.kitex.mom.mdm.feign.RemoteMdmFactoryAccessService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 实际认证和Spring构造边界；仅替换已有MDM远程合同，不模拟网关已校验工厂。 */
class MesActualFactoryAccessServiceTest {

    private AnnotationConfigApplicationContext context;
    private RemoteMdmFactoryAccessService factories;
    private MesActualFactoryAccessService service;

    @BeforeEach
    void setup() {
        authenticate(7L, 1L);
        TenantContextHolder.setTenantId(1L);
        FactoryContextHolder.setFactoryCode("F1");
        factories = mock(RemoteMdmFactoryAccessService.class);
        when(factories.check(any())).thenReturn(R.ok(true));
        context = new AnnotationConfigApplicationContext();
        context.registerBean(RemoteMdmFactoryAccessService.class, () -> factories);
        context.register(MesActualFactoryAccessService.class);
        context.refresh();
        service = context.getBean(MesActualFactoryAccessService.class);
    }

    @AfterEach
    void cleanup() {
        if (context != null) context.close();
        SecurityContextHolder.clearContext();
        TenantContextHolder.clear();
        FactoryContextHolder.clear();
    }

    @Test
    void eachAccessLevelChecksTheActualActorAndCurrentFactory() {
        for (String permission : List.of("read", "write", "admin")) {
            assertEquals("F1", service.require(permission));
        }
        var requests = ArgumentCaptor.forClass(MdmFactoryAccessCheck.class);
        verify(factories, times(3)).check(requests.capture());
        assertEquals(List.of("read", "write", "admin"), requests.getAllValues().stream()
                .map(MdmFactoryAccessCheck::getRequiredPermission).toList());
        assertTrue(requests.getAllValues().stream().allMatch(r -> r.getUserId().equals(7L)
                && r.getFactoryCode().equals("F1")));
    }

    @Test
    void anUnboundFactoryHeaderCannotRead() {
        FactoryContextHolder.setFactoryCode("OTHER_FACTORY");
        when(factories.check(any())).thenReturn(R.ok(false));

        assertThrows(CheckedException.class, () -> service.require("read"));
    }

    @Test
    void readOnlyAccessCannotWrite() {
        when(factories.check(any())).thenAnswer(invocation -> R.ok(
                "read".equals(((MdmFactoryAccessCheck) invocation.getArgument(0)).getRequiredPermission())));
        assertEquals("F1", service.require("read"));

        assertThrows(CheckedException.class, () -> service.require("write"));
        assertThrows(CheckedException.class, () -> service.require("admin"));
    }

    @Test
    void contextTenantMustMatchTheAuthenticatedTenantBeforeRemoteAccess() {
        TenantContextHolder.setTenantId(2L);

        assertThrows(CheckedException.class, () -> service.require("read"));
        verifyNoMoreInteractions(factories);
    }

    @Test
    void positiveTenantAndUserIdentitiesAreRequired() {
        for (Long tenant : Arrays.asList(null, 0L, -1L)) {
            TenantContextHolder.setTenantId(tenant);
            assertThrows(CheckedException.class, () -> service.require("read"));
        }
        TenantContextHolder.setTenantId(1L);
        for (Long actor : Arrays.asList(null, 0L, -1L)) {
            authenticate(actor, 1L);
            assertThrows(CheckedException.class, () -> service.require("read"));
        }
        verifyNoMoreInteractions(factories);
    }

    @Test
    void missingOrUnauthenticatedPrincipalsCannotAccess() {
        SecurityContextHolder.clearContext();
        assertThrows(CheckedException.class, () -> service.require("read"));
        var actor = actor(7L, 1L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(actor, "unused"));
        assertThrows(CheckedException.class, () -> service.require("read"));
        verifyNoMoreInteractions(factories);
    }

    @Test
    void isolationBypassAndMissingFactoryAreRejectedBeforeRemoteAccess() {
        TenantContextHolder.setTenantSkip();
        assertThrows(CheckedException.class, () -> service.require("read"));
        TenantContextHolder.clear();
        TenantContextHolder.setTenantId(1L);
        FactoryContextHolder.setFactorySkip();
        assertThrows(CheckedException.class, () -> service.require("read"));
        FactoryContextHolder.clear();
        assertThrows(CheckedException.class, () -> service.require("read"));
        verifyNoMoreInteractions(factories);
    }

    @Test
    void unknownAccessLevelsCannotBecomeReadAccess() {
        for (String permission : Arrays.asList(null, "", "delete", "READ", " read ")) {
            assertThrows(CheckedException.class, () -> service.require(permission));
        }
        verifyNoMoreInteractions(factories);
    }

    @Test
    void missingRejectedAndFailedReceiptsCloseAccess() {
        for (R<Boolean> receipt : Arrays.asList(null, R.<Boolean>ok(null), R.ok(false), R.ok(true).setCode(1))) {
            when(factories.check(any())).thenReturn(receipt);
            assertThrows(CheckedException.class, () -> service.require("read"));
        }
    }

    @Test
    void authorizationTransportFailureClosesAccessWithARetryableExplanation() {
        when(factories.check(any())).thenThrow(new IllegalStateException("unavailable"));

        var failure = assertThrows(CheckedException.class, () -> service.require("write"));
        assertTrue(failure.getMessage().contains("核验"));
        assertNotNull(failure.getCause());
    }

    @Test
    void revocationIsRecheckedInsteadOfReusingAnEarlierGrant() {
        assertEquals("F1", service.require("write"));
        when(factories.check(any())).thenReturn(R.ok(false));

        assertThrows(CheckedException.class, () -> service.require("write"));
        verify(factories, times(2)).check(any());
    }

    @Test
    void largeTenantIdentityRemainsExact() {
        authenticate(7L, 9007199254740993L);
        TenantContextHolder.setTenantId(9007199254740993L);

        assertEquals("F1", service.require("read"));
        verify(factories).check(any());
    }

    private void authenticate(Long id, Long tenant) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(actor(id, tenant), "unused", List.of()));
    }

    private MomUser actor(Long id, Long tenant) {
        return new MomUser(id, "operator", 1L, null, null, null, "Operator", null,
                tenant, "unused", true, true, "USER", true, true, List.of());
    }
}
