package com.kitex.mom.mdm.controller;

import com.kitex.common.security.service.MomUser;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.mom.mdm.agent.service.MdmAgentActualScopeAccess;
import com.kitex.mom.mdm.entity.MdmUserShiftBindEntity;
import com.kitex.mom.mdm.service.MdmClassesService;
import com.kitex.mom.mdm.service.MdmUserShiftBindService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MdmUserShiftBindControllerTest {
    private final MdmAgentActualScopeAccess scopeAccess = mock(MdmAgentActualScopeAccess.class);
    @AfterEach
    void clearIdentity() {
        SecurityContextHolder.clearContext();
        FactoryContextHolder.clear();
    }

    @Test
    void currentReadRecalculatesInsteadOfReusingAnOldFactoryBinding() {
        var service = mock(MdmUserShiftBindService.class);
        var classes = mock(MdmClassesService.class);
        var binding = new MdmUserShiftBindEntity();
        binding.setShiftCode("261024A");
        when(service.getById(21L)).thenReturn(binding);
        when(classes.getClassesRule(any(LocalDateTime.class))).thenReturn("261030D");
        login();
        var result = new MdmUserShiftBindController(service, classes, scopeAccess).currentBindings();
        assertEquals(0, result.getCode());
        assertEquals("261030D", result.getData());
        verifyZeroInteractions(service);
    }

    @Test
    void currentReadDoesNotCreateAPersistentBinding() {
        var service = mock(MdmUserShiftBindService.class);
        var classes = mock(MdmClassesService.class);
        when(classes.getClassesRule(any(LocalDateTime.class))).thenReturn("261024A");
        login();
        var result = new MdmUserShiftBindController(service, classes, scopeAccess).currentBindings();
        assertEquals("261024A", result.getData());
        verifyZeroInteractions(service);
    }

    @Test
    void offShiftReturnsNoShiftAndDoesNotCreateABinding() {
        var service = mock(MdmUserShiftBindService.class);
        var classes = mock(MdmClassesService.class);
        FactoryContextHolder.setFactoryCode("E2E-ELEC");
        var result = new MdmUserShiftBindController(service, classes, scopeAccess).currentBindings();
        assertNull(result.getData());
        verify(scopeAccess).require("E2E-ELEC", "read");
        verifyZeroInteractions(service);
    }

    @Test
    void missingOrUnauthorizedFactoryCannotReadOrWriteShiftBindings() {
        var service = mock(MdmUserShiftBindService.class);
        var classes = mock(MdmClassesService.class);
        doThrow(new IllegalStateException("无工厂权限")).when(scopeAccess).require(null, "read");
        var controller = new MdmUserShiftBindController(service, classes, scopeAccess);
        assertThrows(IllegalStateException.class, controller::currentBindings);
        assertThrows(IllegalStateException.class, controller::bind);
        verifyZeroInteractions(service, classes);
    }

    @Test
    void ruleEndpointReturnsTheShiftAsDataInsteadOfMessage() {
        var classes = mock(MdmClassesService.class);
        var time = LocalDateTime.of(2026, 11, 2, 8, 0);
        when(classes.getClassesRule(time)).thenReturn("261102D");
        assertEquals("261102D", new MdmClassesController(classes).getClassesRule(time).getData());
    }

    private void login() {
        var user = mock(MomUser.class);
        when(user.getId()).thenReturn(21L);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, "N/A", List.of()));
    }
}
